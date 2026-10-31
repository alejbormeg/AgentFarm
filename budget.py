"""Local budget admission ledger. Does not call providers or enforce remote billing."""
import argparse
import json
from pathlib import Path
import re
import sqlite3
import time


def integer(value, name, minimum=0):
    if type(value) is not int or not minimum <= value <= 10**12:
        raise ValueError(f'{name} must be an integer between {minimum} and 1000000000000')
    return value


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', value):
        raise ValueError('Identifiers must contain 1-80 letters, digits, underscores or hyphens')
    return value


class BudgetLedger:
    def __init__(self, path):
        self.db = sqlite3.connect(path, timeout=10)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS budget_accounts (
                account TEXT PRIMARY KEY, mode TEXT NOT NULL, shares TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS budget_windows (
                account TEXT NOT NULL REFERENCES budget_accounts(account),
                period TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
                total INTEGER NOT NULL, share INTEGER NOT NULL, used INTEGER NOT NULL,
                observed_used INTEGER, observed_at INTEGER, max_age INTEGER NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY(account,period,start));
            CREATE TABLE IF NOT EXISTS budget_runs (
                account TEXT NOT NULL REFERENCES budget_accounts(account),
                run_id TEXT NOT NULL, amounts TEXT NOT NULL, state TEXT NOT NULL,
                actual TEXT, PRIMARY KEY(account,run_id));
            CREATE TABLE IF NOT EXISTS budget_allocations (
                account TEXT NOT NULL, run_id TEXT NOT NULL,
                period TEXT NOT NULL, start INTEGER NOT NULL, reserved INTEGER NOT NULL,
                PRIMARY KEY(account,run_id,period),
                FOREIGN KEY(account,run_id) REFERENCES budget_runs(account,run_id),
                FOREIGN KEY(account,period,start) REFERENCES budget_windows(account,period,start));
        ''')

    def close(self):
        self.db.close()

    def configure(self, account, mode, shares):
        identifier(account)
        if mode not in ('api', 'subscription') or not isinstance(shares, dict) or not shares:
            raise ValueError('Set api/subscription mode and at least one period share')
        for period, share in shares.items():
            identifier(period)
            if integer(share, 'share_bps') > 10000:
                raise ValueError('A share cannot exceed 10000 basis points (100%)')
        encoded = json.dumps(shares, sort_keys=True)
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            prior = self.db.execute('SELECT * FROM budget_accounts WHERE account=?', (account,)).fetchone()
            if prior:
                if (prior['mode'], prior['shares']) != (mode, encoded):
                    raise ValueError('Existing account policy is immutable; do not reset its ledger')
                return {'state': 'already_configured'}
            self.db.execute('INSERT INTO budget_accounts VALUES (?,?,?)', (account, mode, encoded))
        return {'state': 'configured', 'unit': 'usd_micros' if mode == 'api' else 'basis_points'}

    def account(self, account):
        row = self.db.execute('SELECT * FROM budget_accounts WHERE account=?', (account,)).fetchone()
        if not row:
            raise ValueError('Unknown budget account')
        return row

    def open_window(self, account, period, start, end, total, used=None,
                    observed_at=None, max_age=300, source='manual', *, now=None):
        now = int(time.time()) if now is None else now
        integer(start, 'start'); integer(end, 'end'); integer(total, 'total', 1)
        integer(max_age, 'max_age', 1)
        if start >= end or max_age > 86400 or source not in ('manual', 'provider'):
            raise ValueError('Invalid window, observation age or source')
        if (used is None) != (observed_at is None):
            raise ValueError('Usage and observation timestamp must be supplied together')
        if used is not None:
            integer(used, 'used'); integer(observed_at, 'observed_at')
            if not start <= observed_at < end or observed_at > now:
                raise ValueError('Observation must be inside the window and not in the future')
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            config = self.account(account)
            shares = json.loads(config['shares'])
            if period not in shares:
                raise ValueError('Period is absent from account policy')
            if config['mode'] == 'subscription' and total != 10000:
                raise ValueError('Subscription windows use 10000 basis points, never USD')
            overlap = self.db.execute('''SELECT 1 FROM budget_windows
                WHERE account=? AND period=? AND start<? AND end>?''',
                (account, period, end, start)).fetchone()
            if overlap:
                raise ValueError('Cannot replace or overlap an existing budget window')
            self.db.execute('INSERT INTO budget_windows VALUES (?,?,?,?,?,?,?,?,?,?,?)',
                            (account, period, start, end, total, shares[period], used or 0,
                             used, observed_at, max_age, source))
        return {'state': 'window_opened'}

    def observe(self, account, period, start, used, observed_at, *, now=None):
        now = int(time.time()) if now is None else now
        integer(used, 'used'); integer(observed_at, 'observed_at')
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            row = self.db.execute('SELECT * FROM budget_windows WHERE account=? AND period=? AND start=?',
                                  (account, period, start)).fetchone()
            if not row or not row['start'] <= observed_at < row['end'] or observed_at > now:
                raise ValueError('Observation is outside the requested window or in the future')
            if ((row['observed_at'] is not None and observed_at <= row['observed_at'])
                    or (row['observed_used'] is not None and used < row['observed_used'])):
                raise ValueError('Observations must advance in time without decreasing usage')
            # A snapshot never refunds locally accounted usage or in-flight reservations.
            self.db.execute('''UPDATE budget_windows SET used=max(used,?), observed_used=?, observed_at=?
                WHERE account=? AND period=? AND start=?''', (used, used, observed_at, account, period, start))
        return {'state': 'observed'}

    def _status(self, account, now):
        config = self.account(account)
        windows, reasons = [], []
        unresolved = self.db.execute('''SELECT 1 FROM budget_runs WHERE account=?
            AND state IN ('unknown','overrun','crossed_reset') LIMIT 1''', (account,)).fetchone()
        if unresolved:
            reasons.append('unreconciled_usage')
        old_holds = self.db.execute('''SELECT 1 FROM budget_allocations a
            JOIN budget_runs r USING(account,run_id)
            JOIN budget_windows w USING(account,period,start)
            WHERE a.account=? AND r.state='reserved' AND NOT (w.start<=? AND ?<w.end) LIMIT 1''',
            (account, now, now)).fetchone()
        if old_holds:
            reasons.append('reservation_crossed_reset')
        for period in json.loads(config['shares']):
            row = self.db.execute('''SELECT * FROM budget_windows
                WHERE account=? AND period=? AND start<=? AND ?<end''',
                (account, period, now, now)).fetchone()
            if not row:
                reasons.append('missing_active_window:' + period)
                continue
            held = self.db.execute('''SELECT coalesce(sum(a.reserved),0) FROM budget_allocations a
                JOIN budget_runs r USING(account,run_id)
                WHERE a.account=? AND a.period=? AND a.start=? AND r.state IN ('reserved','unknown')''',
                (account, period, row['start'])).fetchone()[0]
            cap = row['total'] * row['share'] // 10000
            remaining = max(0, cap - row['used'] - held)
            if row['observed_at'] is None or not 0 <= now - row['observed_at'] <= row['max_age']:
                reasons.append('missing_or_stale_usage:' + period)
            if remaining == 0:
                reasons.append('budget_exhausted:' + period)
            windows.append({'period': period, 'start': row['start'], 'end': row['end'],
                            'cap': cap, 'used': row['used'], 'reserved': held, 'remaining': remaining,
                            'observed_at': row['observed_at'], 'source': row['source']})
        return {'state': 'paused' if reasons else 'available', 'account': account,
                'mode': config['mode'], 'unit': 'usd_micros' if config['mode'] == 'api' else 'basis_points',
                'reasons': reasons, 'windows': windows}

    def status(self, account, *, now=None):
        with self.db:
            self.db.execute('BEGIN')
            return self._status(account, int(time.time()) if now is None else now)

    def _amounts(self, account, amounts, minimum):
        config = self.account(account)
        if not isinstance(amounts, dict) or amounts.keys() != json.loads(config['shares']).keys():
            raise ValueError('Provide amounts for every configured window, without extras')
        for value in amounts.values():
            integer(value, 'amount', minimum)
        if config['mode'] == 'api' and len(set(amounts.values())) != 1:
            raise ValueError('The same API cost must count against every time window')
        return json.dumps(amounts, sort_keys=True)

    def reserve(self, account, run_id, amounts, *, now=None):
        identifier(run_id)
        now = int(time.time()) if now is None else now
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            encoded = self._amounts(account, amounts, 1)
            prior = self.db.execute('SELECT * FROM budget_runs WHERE account=? AND run_id=?',
                                    (account, run_id)).fetchone()
            if prior:
                if prior['amounts'] != encoded:
                    raise ValueError('Run ID already exists with different reservation amounts')
                return {'state': 'duplicate', 'run_id': run_id, 'previous_state': prior['state']}
            status = self._status(account, now)
            for window in status['windows']:
                if amounts[window['period']] > window['remaining']:
                    status['reasons'].append('insufficient_budget:' + window['period'])
            if status['reasons']:
                return status | {'state': 'paused'}
            self.db.execute('INSERT INTO budget_runs VALUES (?,?,?,?,NULL)',
                            (account, run_id, encoded, 'reserved'))
            for window in status['windows']:
                self.db.execute('INSERT INTO budget_allocations VALUES (?,?,?,?,?)',
                                (account, run_id, window['period'], window['start'], amounts[window['period']]))
        return {'state': 'reserved', 'run_id': run_id, 'amounts': amounts}

    def settle(self, account, run_id, actual, *, now=None):
        now = int(time.time()) if now is None else now
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            row = self.db.execute('SELECT * FROM budget_runs WHERE account=? AND run_id=?',
                                  (account, run_id)).fetchone()
            if not row:
                raise ValueError('Unknown reservation')
            encoded = None if actual is None else self._amounts(account, actual, 0)
            if row['state'] in ('settled', 'overrun', 'crossed_reset'):
                if row['actual'] != encoded:
                    raise ValueError('Finalized usage cannot be rewritten')
                return {'state': 'already_settled'}
            if actual is None:
                self.db.execute("UPDATE budget_runs SET state='unknown' WHERE account=? AND run_id=?",
                                (account, run_id))
                return {'state': 'unknown', 'reason': 'usage_required_before_more_work'}
            overrun = any(actual[p] > v for p, v in json.loads(row['amounts']).items())
            crossed_reset = self.db.execute('''SELECT 1 FROM budget_allocations a
                JOIN budget_windows w USING(account,period,start)
                WHERE a.account=? AND a.run_id=? AND w.end<=? LIMIT 1''',
                (account, run_id, now)).fetchone()
            for allocation in self.db.execute('SELECT * FROM budget_allocations WHERE account=? AND run_id=?',
                                               (account, run_id)).fetchall():
                self.db.execute('''UPDATE budget_windows SET used=used+?
                    WHERE account=? AND period=? AND start=?''',
                    (actual[allocation['period']], account, allocation['period'], allocation['start']))
            state = 'overrun' if overrun else 'crossed_reset' if crossed_reset else 'settled'
            self.db.execute('UPDATE budget_runs SET state=?,actual=? WHERE account=? AND run_id=?',
                            (state, encoded, account, run_id))
        return {'state': state}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', default='budget.db')
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('configure', 'open-window', 'observe', 'reserve', 'settle'):
        sub.add_parser(command).add_argument('file', type=Path)
    sub.add_parser('status').add_argument('account')
    args = parser.parse_args(argv)
    ledger = None
    try:
        ledger = BudgetLedger(args.db)
        if args.command == 'status':
            result = ledger.status(args.account)
        else:
            data = json.loads(args.file.read_text(encoding='utf-8'))
            if not isinstance(data, dict) or 'now' in data:
                raise ValueError('Input must be an object; the clock cannot be overridden')
            result = getattr(ledger, args.command.replace('-', '_'))(**data)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 1 if result['state'] in ('paused', 'unknown', 'overrun', 'crossed_reset', 'duplicate') else 0
    except (ValueError, TypeError, OSError, sqlite3.Error) as error:
        print(json.dumps({'state': 'error', 'error': str(error)}, ensure_ascii=False))
        return 2
    finally:
        if ledger is not None:
            ledger.close()


if __name__ == '__main__':
    raise SystemExit(main())
