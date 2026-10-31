import unittest
from coordinator import connect, enqueue, simulate


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.db = connect(':memory:')
        self.addCleanup(self.db.close)

    def task(self, ident='T1'):
        return dict(id=ident, project='demo', title='Test', acceptance=['Evidence'], allowed_paths=['docs/'])

    def test_idempotency_and_spec_conflict(self):
        self.assertEqual(enqueue(self.db, self.task()), 'enqueued')
        self.assertEqual(enqueue(self.db, self.task()), 'already_enqueued')
        changed = self.task() | {'title': 'Different'}
        with self.assertRaises(ValueError):
            enqueue(self.db, changed)
        self.assertEqual(self.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 1)

    def test_capacity_prevents_new_work(self):
        enqueue(self.db, self.task())
        enqueue(self.db, self.task('T2'))
        result = simulate(self.db, max_pending=1)
        self.assertEqual(result['implementation_evidence'], [])
        self.assertEqual(result['mode'], 'simulation')
        self.assertEqual(simulate(self.db, max_pending=1)['reason'], 'review_capacity')
        self.assertEqual(self.db.execute("SELECT state FROM tasks WHERE id='T2'").fetchone()[0], 'ready')

    def test_daily_limit(self):
        enqueue(self.db, self.task())
        enqueue(self.db, self.task('T2'))
        simulate(self.db, max_daily=1)
        self.assertEqual(simulate(self.db, max_daily=1)['reason'], 'daily_limit')

    def test_crashed_work_is_not_repeated(self):
        enqueue(self.db, self.task())
        with self.db:
            self.db.execute("UPDATE tasks SET state='running'")
        self.assertEqual(simulate(self.db)['reason'], 'running_task_requires_reconciliation')
        self.assertEqual(self.db.execute('SELECT count(*) FROM runs').fetchone()[0], 0)

    def test_rejects_escape_paths(self):
        for path in ('../private', '/etc', 'docs/../../private', 'C:\\private'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                enqueue(self.db, self.task() | {'allowed_paths': [path]})

    def test_idle(self):
        self.assertEqual(simulate(self.db), {'state': 'idle'})


if __name__ == '__main__':
    unittest.main()
