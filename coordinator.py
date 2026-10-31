"""Local coordination simulator. Never invokes agents or executes task content."""
import argparse
import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import PurePosixPath


def connect(path):
    db = sqlite3.connect(path, timeout=10)
    db.row_factory = sqlite3.Row
    db.executescript('''
        CREATE TABLE IF NOT EXISTS tasks (
            project TEXT, id TEXT, spec TEXT NOT NULL, hash TEXT NOT NULL,
            state TEXT NOT NULL, PRIMARY KEY(project, id));
        CREATE TABLE IF NOT EXISTS runs (
            id INTEGER PRIMARY KEY, project TEXT, task TEXT, hash TEXT,
            started TEXT, mode TEXT, result TEXT);
    ''')
    return db


def validate(spec):
    if not isinstance(spec, dict):
        raise ValueError('Task must be an object')
    for key in ('id', 'project', 'title'):
        if not isinstance(spec.get(key), str) or not spec[key].strip():
            raise ValueError(f'Missing string: {key}')
    for key in ('acceptance', 'allowed_paths'):
        values = spec.get(key)
        if not isinstance(values, list) or not values:
            raise ValueError(f'Missing nonempty list: {key}')
        if any(not isinstance(v, str) or not v.strip() for v in values):
            raise ValueError(f'Invalid entries: {key}')
    for path in spec['allowed_paths']:
        if PurePosixPath(path).is_absolute() or '..' in PurePosixPath(path).parts or '\\' in path or ':' in path or path in ('.', './'):
            raise ValueError('Paths must be relative, scoped POSIX paths')


def enqueue(db, spec):
    validate(spec)
    payload = json.dumps(spec, sort_keys=True, ensure_ascii=False)
    digest = hashlib.sha256(payload.encode()).hexdigest()
    with db:
        db.execute('BEGIN IMMEDIATE')
        prior = db.execute('SELECT hash FROM tasks WHERE project=? AND id=?',
                           (spec['project'], spec['id'])).fetchone()
        if prior:
            if prior['hash'] != digest:
                raise ValueError('Task ID already exists with a different specification')
            return 'already_enqueued'
        db.execute('INSERT INTO tasks VALUES (?,?,?,?,?)',
                   (spec['project'], spec['id'], payload, digest, 'ready'))
    return 'enqueued'


def simulate(db, max_pending=2, max_daily=5):
    if max_pending < 1 or max_daily < 1:
        raise ValueError('Limits must be positive')
    now = datetime.now(timezone.utc).isoformat()
    with db:
        db.execute('BEGIN IMMEDIATE')
        if db.execute("SELECT count(*) FROM tasks WHERE state='running'").fetchone()[0]:
            return {'state': 'paused', 'reason': 'running_task_requires_reconciliation'}
        pending = db.execute("SELECT count(*) FROM tasks WHERE state='review'").fetchone()[0]
        if pending >= max_pending:
            return {'state': 'paused', 'reason': 'review_capacity'}
        daily = db.execute('SELECT count(*) FROM runs WHERE substr(started,1,10)=?', (now[:10],)).fetchone()[0]
        if daily >= max_daily:
            return {'state': 'paused', 'reason': 'daily_limit'}
        task = db.execute("SELECT * FROM tasks WHERE state='ready' ORDER BY project,id LIMIT 1").fetchone()
        if task is None:
            return {'state': 'idle'}
        db.execute("UPDATE tasks SET state='running' WHERE project=? AND id=?", (task['project'], task['id']))
        run_id = db.execute('INSERT INTO runs(project,task,hash,started,mode) VALUES (?,?,?,?,?)',
                            (task['project'], task['id'], task['hash'], now, 'simulation')).lastrowid
    result = {'run_id': run_id, 'state': 'review', 'mode': 'simulation',
              'task': task['id'], 'project': task['project'], 'spec_hash': task['hash'],
              'summary': 'Coordination simulated; no code implemented, no agent invoked.',
              'implementation_evidence': [], 'cost': None}
    with db:
        db.execute('UPDATE runs SET result=? WHERE id=?', (json.dumps(result), run_id))
        db.execute("UPDATE tasks SET state='review' WHERE project=? AND id=?", (task['project'], task['id']))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', default='state.db')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('enqueue').add_argument('file')
    for command in ('simulate', 'status', 'report'):
        sub.add_parser(command)
    args = parser.parse_args()
    db = connect(args.db)
    try:
        if args.command == 'enqueue':
            with open(args.file, encoding='utf-8') as source:
                result = enqueue(db, json.load(source))
        elif args.command == 'simulate':
            result = simulate(db, int(os.getenv('ACP_MAX_PENDING', '2')), int(os.getenv('ACP_MAX_DAILY_RUNS', '5')))
        elif args.command == 'status':
            result = [dict(r) for r in db.execute('SELECT project,id,hash,state FROM tasks ORDER BY project,id')]
        else:
            result = [dict(r) | {'result': json.loads(r['result']) if r['result'] else None}
                      for r in db.execute('SELECT * FROM runs ORDER BY id')]
        print(json.dumps(result, indent=2, ensure_ascii=False))
    except (ValueError, OSError) as error:
        parser.exit(2, f'{error}\n')
    finally:
        db.close()


if __name__ == '__main__':
    main()
