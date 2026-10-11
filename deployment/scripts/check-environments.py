#!/usr/bin/env python3
"""Validate configuration isolation without printing resolved credentials."""
import json
from pathlib import Path
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
configs = {}
values = {}
for env in ['dev', 'prod']:
    configs[env] = json.loads(subprocess.check_output(
        [str(ROOT / 'scripts/compose.sh'), env, 'config', '--format', 'json'], text=True))
    file = ROOT / f'.env.{env}'
    values[env] = dict(line.split('=', 1) for line in file.read_text().splitlines()
                       if line and not line.startswith('#'))
    assert configs[env]['name'] == f'xmut-{env}'
    for path in [file, ROOT / 'secrets' / env / 'accounts.md', ROOT / 'secrets' / env / 'backend.env']:
        assert stat.S_IMODE(path.stat().st_mode) == 0o600, f'Private file permissions: {path}'
        subprocess.run(['git', 'check-ignore', '--quiet', str(path)], cwd=ROOT, check=True)
    for service in configs[env]['services'].values():
        assert '@sha256:' in service['image'], 'Images must be pinned by digest'
        assert all(p['host_ip'] == '127.0.0.1' for p in service.get('ports', []))
for key in values['dev']:
    if any(word in key for word in ['PASSWORD', 'TOKEN', 'IDENTITY_VALUE']):
        assert values['dev'][key] != values['prod'][key], f'Shared credential: {key}'
for kind in ['networks', 'volumes']:
    dev = {v['name'] for v in configs['dev'][kind].values()}
    prod = {v['name'] for v in configs['prod'][kind].values()}
    assert dev.isdisjoint(prod), f'Shared {kind}'
ports = lambda env: {p['published'] for s in configs[env]['services'].values() for p in s.get('ports', [])}
assert ports('dev').isdisjoint(ports('prod')), 'Overlapping ports'
print('PASS: dev/prod project names, networks, volumes, ports and credentials are independent.')
print('PASS: images pinned; host ports loopback-only; private files mode 600 and Git-ignored.')
