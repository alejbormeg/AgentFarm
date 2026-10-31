"""Read-only commit scope gate. A passed report is never implementation approval."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from coordinator import validate


POLICY_VERSION = 'scope-v1'
PROTECTED_PATHS = ('AGENTS.md', '.github/', '.agentfarm/', '.git/')
REGULAR_MODES = {'100644', '100755'}
RESERVED_NAMES = {'con', 'prn', 'aux', 'nul'} | {
    prefix + str(number) for prefix in ('com', 'lpt') for number in range(1, 10)}


def scoped_path(value, *, directory=False):
    """Require literal portable paths; a trailing slash explicitly scopes a directory."""
    if not isinstance(value, str) or not value:
        raise ValueError('Paths must be nonempty strings')
    path = value[:-1] if directory and value.endswith('/') else value
    if (not path or any(ord(c) < 32 or ord(c) == 127 for c in path)
            or any(c in path for c in '\\:*?[]<>|"')
            or any(part in ('', '.', '..') or part.endswith((' ', '.'))
                   or part.split('.')[0].casefold() in RESERVED_NAMES
                   for part in path.split('/'))):
        raise ValueError('Paths must be literal relative POSIX paths')
    return value


def matches(path, scope):
    return path.startswith(scope) if scope.endswith('/') else path == scope


def git(repo, *args, accepted=(0,)):
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GIT_NO_LAZY_FETCH='1')
    try:
        result = subprocess.run(
            ['git', '--no-replace-objects', '-C', str(repo), *args],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=15, env=env, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError('Git unavailable or command timed out') from error
    if result.returncode not in accepted:
        raise ValueError('Git could not read the requested repository or commits')
    return result


def commit_sha(repo, value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-fA-F]{40}|[0-9a-fA-F]{64}', value):
        raise ValueError('Provide a full immutable commit SHA, not a branch or revision expression')
    resolved = git(repo, 'rev-parse', '--verify', value + '^{commit}').stdout.decode().strip()
    if resolved != value.lower():
        raise ValueError('The SHA must identify a commit object directly')
    return resolved


def snapshot(repo, sha):
    entries = {}
    for record in git(repo, 'ls-tree', '-r', '-z', '--full-tree', sha).stdout.split(b'\0'):
        if not record:
            continue
        metadata, raw_path = record.split(b'\t', 1)
        mode, kind, oid = metadata.decode('ascii').split()
        try:
            path = raw_path.decode('utf-8')
        except UnicodeDecodeError as error:
            raise ValueError('Non-UTF-8 Git paths are unsupported') from error
        entries[path] = (mode, kind, oid)
    return entries


def check_scope(repo, spec, base, candidate):
    validate(spec)
    allowed = [scoped_path(p, directory=True) for p in spec['allowed_paths']]
    base = commit_sha(repo, base)
    candidate = commit_sha(repo, candidate)
    if git(repo, 'merge-base', '--is-ancestor', base, candidate, accepted=(0, 1)).returncode:
        raise ValueError('Candidate must descend from the specified base commit')
    before, after = snapshot(repo, base), snapshot(repo, candidate)
    folded_paths = {}
    for path in after:
        folded_paths.setdefault(path.casefold(), []).append(path)
    changes, violations = [], []
    for path in sorted(before.keys() | after.keys()):
        old, new = before.get(path), after.get(path)
        if old == new:
            continue
        status = 'added' if old is None else 'deleted' if new is None else 'modified'
        changes.append({'path': path, 'status': status,
                        'before': old, 'after': new})
        try:
            scoped_path(path)
        except ValueError:
            violations.append({'path': path, 'reason': 'unsupported_path'})
        if any(matches(path.casefold(), p.casefold()) for p in PROTECTED_PATHS):
            violations.append({'path': path, 'reason': 'protected_path'})
        if not any(matches(path, scope) for scope in allowed):
            violations.append({'path': path, 'reason': 'outside_allowed_paths'})
        if new and len(folded_paths[path.casefold()]) > 1:
            violations.append({'path': path, 'reason': 'case_collision'})
        if any(entry and entry[0] not in REGULAR_MODES for entry in (old, new)):
            violations.append({'path': path, 'reason': 'symlink_or_submodule'})
    payload = json.dumps(spec, sort_keys=True, ensure_ascii=False)
    return {
        'mode': 'scope_validation', 'policy_version': POLICY_VERSION,
        'protected_paths': list(PROTECTED_PATHS),
        'spec_hash': hashlib.sha256(payload.encode()).hexdigest(),
        'base_sha': base, 'candidate_sha': candidate,
        'state': 'passed' if changes and not violations else 'blocked',
        'reason': 'no_changes' if not changes else 'scope_violation' if violations else 'within_scope',
        'changes': changes, 'violations': violations,
        'implementation_verified': False, 'human_approved': False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--spec', type=Path, required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--candidate', required=True)
    args = parser.parse_args(argv)
    try:
        spec = json.loads(args.spec.read_text(encoding='utf-8'))
        result = check_scope(args.repo, spec, args.base, args.candidate)
    except (ValueError, OSError) as error:
        print(json.dumps({'state': 'error', 'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result['state'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
