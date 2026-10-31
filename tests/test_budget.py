from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import tempfile
import threading
import unittest

from budget import BudgetLedger, main
from contextlib import redirect_stdout
from io import StringIO


class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.ledger = BudgetLedger(':memory:')
        self.addCleanup(self.ledger.close)
        self.ledger.configure('codex', 'subscription', {'weekly': 5000})
        self.window('weekly')

    def window(self, period, start=100, end=200, used=0, observed_at=100, **kwargs):
        self.ledger.open_window('codex', period, start, end, 10000,
                                used=used, observed_at=observed_at, now=100, **kwargs)

    def reserve(self, ident='boxer-1', amount=1000, **kwargs):
        return self.ledger.reserve('codex', ident, {'weekly': amount}, now=110, **kwargs)

    def settle(self, actual=500, ident='boxer-1'):
        return self.ledger.settle('codex', ident,
                                  None if actual is None else {'weekly': actual}, now=120)

    def status(self, now=120):
        return self.ledger.status('codex', now=now)

    def test_50_percent_is_a_quota_share_not_subscription_price(self):
        self.assertEqual(self.status()['unit'], 'basis_points')
        self.assertEqual(self.status()['windows'][0]['cap'], 5000)
        self.assertEqual(self.reserve(amount=5000)['state'], 'reserved')
        self.assertEqual(self.reserve('boxer-2', 1)['state'], 'paused')

    def test_reservations_and_actual_usage_consume_one_shared_budget(self):
        self.reserve(amount=3000)
        self.assertEqual(self.status()['windows'][0]['remaining'], 2000)
        self.assertEqual(self.settle(1200)['state'], 'settled')
        self.assertEqual(self.status()['windows'][0]['remaining'], 3800)
        self.assertEqual(self.reserve('boxer-2', 3801)['state'], 'paused')

    def test_no_duplicate_execution_or_conflicting_run_ids(self):
        self.reserve()
        self.assertEqual(self.reserve()['state'], 'duplicate')
        with self.assertRaises(ValueError):
            self.reserve(amount=999)
        self.settle()
        self.assertEqual(self.reserve()['previous_state'], 'settled')
        self.assertEqual(self.settle()['state'], 'already_settled')
        self.assertEqual(self.status()['windows'][0]['used'], 500)
        with self.assertRaises(ValueError):
            self.settle(0)

    def test_unknown_cost_blocks_account_and_preserves_hold_until_reconciled(self):
        self.reserve()
        self.assertEqual(self.settle(None)['state'], 'unknown')
        self.assertIn('unreconciled_usage', self.reserve('boxer-2')['reasons'])
        self.assertEqual(self.status()['windows'][0]['reserved'], 1000)
        self.settle(400)
        self.assertEqual(self.reserve('boxer-2')['state'], 'reserved')

    def test_overrun_records_real_usage_and_blocks_further_work(self):
        self.reserve()
        self.assertEqual(self.settle(1100)['state'], 'overrun')
        self.assertEqual(self.status()['windows'][0]['used'], 1100)
        self.assertIn('unreconciled_usage', self.reserve('boxer-2')['reasons'])

    def test_cancelled_before_provider_work_can_settle_zero(self):
        self.reserve()
        self.settle(0)
        self.assertEqual(self.status()['windows'][0]['remaining'], 5000)

    def test_missing_or_stale_telemetry_pauses_work(self):
        self.ledger.configure('stale', 'subscription', {'weekly': 5000})
        self.ledger.open_window('stale', 'weekly', 100, 1000, 10000,
                                used=0, observed_at=100, max_age=5, now=100)
        self.assertIn('missing_or_stale_usage:weekly', self.ledger.status('stale', now=110)['reasons'])
        self.ledger.open_window('codex', 'weekly', 500, 1000, 10000, now=500)
        self.assertIn('missing_or_stale_usage:weekly', self.ledger.reserve(
            'codex', 'later', {'weekly': 1}, now=501)['reasons'])

    def test_missing_or_expired_window_does_not_reset_budget_implicitly(self):
        self.assertIn('missing_active_window:weekly', self.status(now=200)['reasons'])

    def test_new_window_starts_only_at_configured_reset(self):
        self.window('weekly', start=200, end=300, used=None, observed_at=None)
        self.assertEqual(self.status(now=199)['windows'][0]['start'], 100)
        self.assertEqual(self.status(now=200)['windows'][0]['start'], 200)
        self.ledger.observe('codex', 'weekly', 200, 0, 200, now=200)
        self.assertEqual(self.status(now=200)['state'], 'available')

    def test_cross_reset_reservation_cannot_disappear(self):
        self.reserve()
        self.ledger.open_window('codex', 'weekly', 200, 400, 10000,
                                used=0, observed_at=200, now=200)
        self.assertIn('reservation_crossed_reset', self.status(now=201)['reasons'])
        self.assertEqual(self.ledger.settle('codex', 'boxer-1', {'weekly': 200}, now=201)['state'],
                         'crossed_reset')
        self.assertIn('unreconciled_usage', self.status(now=201)['reasons'])

    def test_snapshots_account_for_external_usage_without_refunding_local_charges(self):
        self.reserve()
        self.settle(500)
        self.ledger.observe('codex', 'weekly', 100, 3000, 121, now=121)
        self.assertEqual(self.status(now=121)['windows'][0]['remaining'], 2000)
        self.ledger.reserve('codex', 'boxer-2', {'weekly': 1000}, now=121)
        self.ledger.settle('codex', 'boxer-2', {'weekly': 800}, now=122)
        self.ledger.observe('codex', 'weekly', 100, 3500, 123, now=123)
        self.assertEqual(self.status(now=123)['windows'][0]['used'], 3800)

    def test_snapshot_never_releases_running_reservations(self):
        self.reserve(amount=3000)
        self.ledger.observe('codex', 'weekly', 100, 2000, 111, now=111)
        self.assertEqual(self.status()['windows'][0]['remaining'], 0)

    def test_rejects_stale_future_or_decreasing_observations(self):
        self.ledger.observe('codex', 'weekly', 100, 3000, 110, now=110)
        for used, observed_at in ((2000, 111), (3000, 110), (4000, 130), (4000, 200)):
            with self.subTest(used=used, at=observed_at), self.assertRaises(ValueError):
                self.ledger.observe('codex', 'weekly', 100, used, observed_at, now=120)

    def test_policy_cannot_be_replaced_and_windows_cannot_overlap(self):
        self.assertEqual(self.ledger.configure('codex', 'subscription', {'weekly': 5000})['state'],
                         'already_configured')
        with self.assertRaises(ValueError):
            self.ledger.configure('codex', 'api', {'weekly': 10000})
        with self.assertRaises(ValueError):
            self.window('weekly', start=199, end=300)
        with self.assertRaises(ValueError):
            self.ledger.open_window('codex', 'weekly', 200, 300, 20, now=200)

    def test_all_configured_windows_must_allow_work(self):
        self.ledger.configure('two-pools', 'subscription', {'weekly': 5000, 'five_hour': 8000})
        self.ledger.open_window('two-pools', 'weekly', 100, 300, 10000,
                                used=0, observed_at=100, now=100)
        self.assertIn('missing_active_window:five_hour', self.ledger.reserve(
            'two-pools', 'one', {'weekly': 10, 'five_hour': 10}, now=110)['reasons'])
        self.ledger.open_window('two-pools', 'five_hour', 100, 200, 10000,
                                used=7999, observed_at=100, now=100)
        self.assertEqual(self.ledger.reserve('two-pools', 'one',
                         {'weekly': 100, 'five_hour': 2}, now=110)['state'], 'paused')

    def test_api_cost_counts_against_monthly_and_weekly_limits(self):
        self.ledger.configure('api', 'api', {'monthly': 10000, 'weekly': 5000})
        for period, total in (('monthly', 20_000_000), ('weekly', 5_000_000)):
            self.ledger.open_window('api', period, 100, 200, total,
                                    used=0, observed_at=100, now=100)
        with self.assertRaises(ValueError):
            self.ledger.reserve('api', 'bad', {'monthly': 1, 'weekly': 2}, now=110)
        result = self.ledger.reserve('api', 'one', {'monthly': 2_500_001, 'weekly': 2_500_001}, now=110)
        self.assertEqual(result['state'], 'paused')
        self.assertEqual(self.ledger.reserve('api', 'two',
                         {'monthly': 2_000_000, 'weekly': 2_000_000}, now=110)['state'], 'reserved')

    def test_rejects_invalid_units_missing_windows_and_non_integer_amounts(self):
        for amounts in ({}, {'weekly': -1}, {'weekly': 0}, {'weekly': True},
                        {'weekly': 1.5}, {'weekly': '10'}, {'weekly': 1, 'extra': 1}):
            with self.subTest(amounts=amounts), self.assertRaises(ValueError):
                self.ledger.reserve('codex', 'invalid', amounts, now=110)
        with self.assertRaises(ValueError):
            self.ledger.configure('too-much', 'subscription', {'weekly': 10001})

    def test_zero_share_disables_admission(self):
        self.ledger.configure('disabled', 'subscription', {'weekly': 0})
        self.ledger.open_window('disabled', 'weekly', 100, 200, 10000,
                                used=0, observed_at=100, now=100)
        self.assertEqual(self.ledger.reserve('disabled', 'one', {'weekly': 1}, now=110)['state'], 'paused')

    def test_concurrent_workers_cannot_oversubscribe_and_holds_survive_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = str(Path(folder) / 'budget.db')
            db = BudgetLedger(path)
            db.configure('shared', 'subscription', {'weekly': 5000})
            db.open_window('shared', 'weekly', 100, 200, 10000,
                           used=0, observed_at=100, now=100)
            db.close()
            barrier = threading.Barrier(2)

            def reserve(ident):
                worker = BudgetLedger(path)
                try:
                    barrier.wait(timeout=10)
                    return worker.reserve('shared', ident, {'weekly': 3000}, now=110)['state']
                finally:
                    worker.close()

            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(reserve, ('one', 'two')))
            self.assertCountEqual(results, ['reserved', 'paused'])
            db = BudgetLedger(path)
            try:
                self.assertEqual(db.status('shared', now=120)['windows'][0]['reserved'], 3000)
            finally:
                db.close()

    def test_cli_outputs_json_and_does_not_accept_clock_override(self):
        with tempfile.TemporaryDirectory() as folder:
            policy = Path(folder) / 'policy.json'
            path = str(Path(folder) / 'budget.db')
            policy.write_text(json.dumps({'account': 'cli', 'mode': 'subscription',
                                          'shares': {'weekly': 5000}}))
            output = StringIO()
            with redirect_stdout(output):
                code = main(['--db', path, 'configure', str(policy)])
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(output.getvalue())['state'], 'configured')
            with redirect_stdout(StringIO()):
                self.assertEqual(main(['--db', path, 'status', 'cli']), 1)
            policy.write_text(json.dumps({'account': 'cli', 'now': 100}))
            with redirect_stdout(StringIO()):
                self.assertEqual(main(['--db', path, 'status', 'missing']), 2)
                self.assertEqual(main(['--db', path, 'reserve', str(policy)]), 2)


if __name__ == '__main__':
    unittest.main()
