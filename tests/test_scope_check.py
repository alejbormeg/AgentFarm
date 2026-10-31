import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from coordinator import connect, enqueue
from scope_check import check_scope


class ScopeCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Scope tests')
        self.git('config', 'user.email', 'scope@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.write('docs/a.md', 'initial\n')
        self.write('src/app.py', 'print(1)\n')
        self.git('add', '.')
        self.base = self.commit()
        self.spec = dict(id='M1-001', project='fixture', title='Change docs',
                         acceptance=['Scope report'], allowed_paths=['docs/'])

    def git(self, *args, data=None):
        return subprocess.run(['git', '-C', str(self.repo), *args], input=data,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=self.env, check=True).stdout.decode().strip()

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding='utf-8')

    def commit(self):
        self.git('commit', '-m', 'Fixture change')
        return self.git('rev-parse', 'HEAD')

    def changed(self):
        self.git('add', '--all')
        return self.commit()

    def check(self, candidate=None, spec=None):
        return check_scope(self.repo, spec or self.spec, self.base,
                           candidate or self.git('rev-parse', 'HEAD'))

    def reasons(self, report):
        return {v['reason'] for v in report['violations']}

    def test_records_add_modify_delete_with_commit_and_spec_identity(self):
        self.write('docs/a.md', 'changed\n')
        self.write('docs/new.md', 'new\n')
        candidate = self.changed()
        report = self.check(candidate)
        self.assertEqual(report['state'], 'passed')
        self.assertEqual({c['status'] for c in report['changes']}, {'added', 'modified'})
        self.assertEqual(report['base_sha'], self.base)
        self.assertEqual(report['candidate_sha'], candidate)
        db = connect(':memory:')
        self.addCleanup(db.close)
        enqueue(db, self.spec)
        self.assertEqual(report['spec_hash'], db.execute('SELECT hash FROM tasks').fetchone()[0])
        self.assertFalse(report['human_approved'])
        self.assertFalse(report['implementation_verified'])
        (self.repo / 'docs/a.md').unlink()
        self.assertIn('deleted', {c['status'] for c in self.check(self.changed())['changes']})

    def test_rename_checks_both_source_and_destination(self):
        self.git('mv', 'src/app.py', 'docs/app.py')
        report = self.check(self.commit())
        self.assertEqual(report['state'], 'blocked')
        self.assertIn({'path': 'src/app.py', 'reason': 'outside_allowed_paths'}, report['violations'])
        self.git('mv', 'docs/a.md', 'src/a.md')
        report = self.check(self.commit())
        self.assertIn({'path': 'src/a.md', 'reason': 'outside_allowed_paths'}, report['violations'])

    def test_directory_prefix_does_not_allow_sibling_names(self):
        self.write('docs-secret.txt', 'outside\n')
        self.assertEqual(self.check(self.changed())['state'], 'blocked')

    def test_file_scope_is_exact(self):
        self.spec['allowed_paths'] = ['docs/a.md']
        self.write('docs/a.md', 'allowed\n')
        self.assertEqual(self.check(self.changed())['state'], 'passed')
        self.write('docs/a.md.backup', 'outside\n')
        self.assertEqual(self.check(self.changed())['state'], 'blocked')

    def test_protected_paths_override_explicit_allowed_scopes(self):
        for path in ('AGENTS.md', '.github/workflows/task.yml', '.agentfarm/policy.json'):
            self.write(path, 'protected\n')
        self.spec['allowed_paths'] = ['AGENTS.md', '.github/', '.agentfarm/']
        report = self.check(self.changed())
        self.assertEqual(sum(v['reason'] == 'protected_path' for v in report['violations']), 3)

    def test_protection_cannot_be_bypassed_with_case(self):
        self.write('.GitHub/workflows/task.yml', 'protected\n')
        self.spec['allowed_paths'] = ['.GitHub/']
        self.assertIn('protected_path', self.reasons(self.check(self.changed())))

    def test_symlink_is_blocked_without_following_target(self):
        oid = self.git('hash-object', '-w', '--stdin', data=b'../../private')
        self.git('update-index', '--add', '--cacheinfo', '120000,' + oid + ',docs/link')
        report = self.check(self.commit())
        self.assertIn('symlink_or_submodule', self.reasons(report))

    def test_submodule_is_blocked(self):
        self.git('update-index', '--add', '--cacheinfo', '160000,' + self.base + ',docs/vendor')
        self.assertIn('symlink_or_submodule', self.reasons(self.check(self.commit())))

    def test_mode_only_changes_are_visible(self):
        self.git('update-index', '--chmod=+x', 'docs/a.md')
        report = self.check(self.commit())
        self.assertEqual(report['state'], 'passed')
        self.assertEqual(report['changes'][0]['before'][0], '100644')
        self.assertEqual(report['changes'][0]['after'][0], '100755')

    def test_no_changes_cannot_be_delivered(self):
        report = self.check(self.base)
        self.assertEqual((report['state'], report['reason']), ('blocked', 'no_changes'))

    def test_rejects_mutable_revisions_missing_commits_and_blob_ids(self):
        blob = self.git('rev-parse', 'HEAD:docs/a.md')
        for value in ('HEAD', 'main', self.base[:12], '--all', '0' * 40, blob):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.check(value)

    def test_rejects_unrelated_candidate(self):
        tree = self.git('rev-parse', 'HEAD^{tree}')
        orphan = self.git('commit-tree', tree, '-m', 'Unrelated commit')
        with self.assertRaisesRegex(ValueError, 'descend'):
            self.check(orphan)

    def test_rejects_ambiguous_or_escaping_scopes(self):
        for scope in ('/', './', 'docs//', 'docs/../src', 'C:/private',
                      '\\private', 'docs/*', 'docs/[ab]', 'docs/\n', 'docs/\x00',
                      '.github./', 'docs /', 'docs/NUL', 'docs/com1.txt'):
            with self.subTest(scope=scope), self.assertRaises(ValueError):
                self.check(spec=self.spec | {'allowed_paths': [scope]})

    def test_rejects_changed_paths_with_case_collisions(self):
        oid = self.git('hash-object', '-w', '--stdin', data=b'collision')
        self.git('update-index', '--add', '--cacheinfo', '100644,' + oid + ',docs/A.md')
        self.assertIn('case_collision', self.reasons(self.check(self.commit())))

    def test_rejects_nonportable_committed_paths(self):
        oid = self.git('hash-object', '-w', '--stdin', data=b'nonportable')
        self.git('-c', 'core.protectNTFS=false', 'update-index', '--add',
                 '--cacheinfo', '100644,' + oid + ',docs/file.')
        self.assertIn('unsupported_path', self.reasons(self.check(self.commit())))

    def test_read_only_and_uncommitted_changes_are_not_evidence(self):
        self.write('docs/a.md', 'committed\n')
        candidate = self.changed()
        self.write('src/app.py', 'uncommitted\n')
        self.write('untracked.txt', 'private fixture\n')
        before = self.git('status', '--porcelain=v1', '-uall')
        report = self.check(candidate)
        self.assertEqual(report['state'], 'passed')
        self.assertEqual(self.git('status', '--porcelain=v1', '-uall'), before)
        self.assertEqual((self.repo / 'untracked.txt').read_text(), 'private fixture\n')

    def test_cli_exit_codes_and_json(self):
        self.write('docs/a.md', 'changed\n')
        candidate = self.changed()
        spec_path = self.repo / 'spec.json'
        spec_path.write_text(json.dumps(self.spec), encoding='utf-8')
        script = Path(__file__).resolve().parents[1] / 'scope_check.py'
        for target, code, state in ((candidate, 0, 'passed'), (self.base, 1, 'blocked'),
                                    ('HEAD', 2, 'error')):
            with self.subTest(state=state):
                result = subprocess.run([sys.executable, str(script), '--repo', str(self.repo),
                                         '--spec', str(spec_path), '--base', self.base,
                                         '--candidate', target], capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stderr)
                self.assertEqual(json.loads(result.stdout or result.stderr)['state'], state)


if __name__ == '__main__':
    unittest.main()
