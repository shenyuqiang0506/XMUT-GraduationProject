#!/usr/bin/env python3
"""Validate configuration isolation without printing resolved credentials."""
import json
from pathlib import Path
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
configs = {}
values = {}
# 读取 Compose 解析后的配置做隔离断言；解析结果含凭据，不打印完整配置。
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
# 检查实际网络和数据卷名称互不重叠，防止两套环境复用持久化数据。
for kind in ['networks', 'volumes']:
    dev = {v['name'] for v in configs['dev'][kind].values()}
    prod = {v['name'] for v in configs['prod'][kind].values()}
    assert dev.isdisjoint(prod), f'Shared {kind}'
ports = lambda env: {p['published'] for s in configs[env]['services'].values() for p in s.get('ports', [])}
assert ports('dev').isdisjoint(ports('prod')), 'Overlapping ports'
print('PASS: dev/prod project names, networks, volumes, ports and credentials are independent.')
print('PASS: images pinned; host ports loopback-only; private files mode 600 and Git-ignored.')

# Business JWT/session credentials are also independent and never committed.
import importlib.util
spec=importlib.util.spec_from_file_location('runtime',ROOT/'scripts/run-service.py')
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
auth={}
fingerprints={}
for env in ['dev','prod']:
    private=ROOT/'secrets'/env
    auth[env]=runtime.load_env(private/'auth.env')
    identity=runtime.load_env(private/'identity.env')
    fingerprint=Path(identity['AUTH_PASSWORD_FINGERPRINT_KEY_PATH'])
    assert fingerprint.parent==private and not fingerprint.is_symlink()
    fingerprints[env]=fingerprint.read_bytes()
    assert len(fingerprints[env])==32 and stat.S_IMODE(fingerprint.stat().st_mode)==0o600
    subprocess.run(['git','check-ignore','--quiet',str(fingerprint)],cwd=ROOT,check=True)
    for name in ['auth.env','identity.env','jwt-private.pem','jwt-public.pem']:
        path=private/name
        assert stat.S_IMODE(path.stat().st_mode)==0o600
        subprocess.run(['git','check-ignore','--quiet',str(path)],cwd=ROOT,check=True)
    service_key=private/'booking-service-call.key'
    assert len(service_key.read_bytes())==32 and stat.S_IMODE(service_key.stat().st_mode)==0o600
    for path in [service_key,private/'notification.env']:
        subprocess.run(['git','check-ignore','--quiet',str(path)],cwd=ROOT,check=True)
        assert stat.S_IMODE(path.stat().st_mode)==0o600
    notification=runtime.service_env(env,'notification-service')
    assert notification['NOTIFICATION_DB_USERNAME']==values[env]['NOTIFICATION_DB_USER']
    assert 'DB_PASSWORD' not in notification and 'REDIS_PASSWORD' not in notification and 'SERVICE_CALL_KEY_PATH' not in notification
    assert auth[env]['JWT_ISSUER']=='urn:xmut:'+env+':identity'
    assert auth[env]['JWT_AUDIENCE']=='xmut-'+env+'-api'
    assert Path(auth[env]['IMPORT_STORAGE_DIR']).parent==private
    for service in ['booking-service','gateway','ai-service','notification-service']:
        child=runtime.service_env(env,service)
        assert 'JWT_PRIVATE_KEY_PATH' not in child and 'AUTH_ADMIN_PASSWORD' not in child and 'IDENTITY_DB_PASSWORD' not in child
        assert 'AUTH_PASSWORD_FINGERPRINT_KEY_PATH' not in child
        if service=='gateway':
            assert child['REDIS_PASSWORD']==values[env]['REDIS_PASSWORD'] and 'DB_PASSWORD' not in child
assert auth['dev']['JWT_PUBLIC_KEY_PATH']!=auth['prod']['JWT_PUBLIC_KEY_PATH']
assert (ROOT/'secrets/dev/jwt-public.pem').read_bytes()!=(ROOT/'secrets/prod/jwt-public.pem').read_bytes()
print('PASS: independent dev/prod JWT keys, issuer, audience and import directories; private signing key stays in identity service.')
assert fingerprints['dev']!=fingerprints['prod']
assert (ROOT/'secrets/dev/booking-service-call.key').read_bytes()!=(ROOT/'secrets/prod/booking-service-call.key').read_bytes()
print('PASS: independent password fingerprint keys stay private to identity; gateway only receives its own Redis credential.')
