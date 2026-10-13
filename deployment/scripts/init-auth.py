#!/usr/bin/env python3
"""Generate isolated JWT keys and dev bootstrap credentials; never overwrite."""
import argparse
import os
from pathlib import Path
import secrets
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]

# 独占创建私密环境文件，并转义 shell 特殊字符，供启动器安全读取。
def envfile(path, values):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write('# PRIVATE: never commit. Source with set -a or use run-service.py.\n')
        for key, value in values.items():
            stream.write(key + '=' + shlex.quote(str(value)) + '\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    environment = parser.parse_args().environment
    source = dict(line.split('=', 1) for line in (ROOT / ('.env.' + environment)).read_text().splitlines() if line and not line.startswith('#'))
    if source['APP_ENV'] != environment:
        raise SystemExit('Environment mismatch')
    private = ROOT / 'secrets' / environment
    private.mkdir(parents=True, mode=0o700, exist_ok=True)
    targets = [private / 'jwt-private.pem', private / 'jwt-public.pem', private / 'auth.env', private / 'identity.env', private / 'password-fingerprint.key']
    if any(path.exists() for path in targets):
        raise SystemExit('Authentication files already exist; refusing to rotate or overwrite them.')
    os.chmod(private, 0o700)
    # Restrict permissions before OpenSSL writes the key.
    for path in targets[:2]:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(fd)
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(targets[0])], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['openssl', 'pkey', '-in', str(targets[0]), '-pubout', '-out', str(targets[1])], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # 新环境使用独立随机密钥，JWT 私钥后续轮换不会改变密码幂等指纹。
    fd = os.open(targets[4], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(secrets.token_bytes(32))
    # 其他服务只需公钥即可验证 JWT；私钥路径仅写入下方身份服务配置。
    common = {'JWT_PUBLIC_KEY_PATH': targets[1], 'JWT_ISSUER': 'urn:xmut:' + environment + ':identity',
              'JWT_AUDIENCE': 'xmut-' + environment + '-api', 'JWT_KEY_ID': 'xmut-' + environment + '-v1',
              'IMPORT_STORAGE_DIR': private / 'imports'}
    envfile(targets[2], common)
    port = '3306' if environment == 'dev' else '13306'
    identity = {'JWT_PRIVATE_KEY_PATH': targets[0], 'AUTH_PASSWORD_FINGERPRINT_KEY_PATH': targets[4],
                'IDENTITY_DB_URL': 'jdbc:mysql://127.0.0.1:' + port + '/identity_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                'IDENTITY_DB_USERNAME': source['IDENTITY_DB_USER'], 'IDENTITY_DB_PASSWORD': source['IDENTITY_DB_PASSWORD'],
                'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '6379' if environment == 'dev' else '16379', 'REDIS_PASSWORD': source['REDIS_PASSWORD']}
    # 仅开发环境生成初始化管理员；密码留存在被忽略的本地账号文件中。
    # 生成账号记录不代表账号已经入库，还需以 bootstrap-admin 配置启动身份服务。
    if environment == 'dev':
        identity.update(AUTH_ADMIN_USERNAME='admin_dev', AUTH_ADMIN_PASSWORD=secrets.token_hex(24))
        record = private / 'business-accounts.md'
        fd = os.open(record, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            stream.write('# dev 正式业务初始化账号\n\n仅在 bootstrap-admin 首次启动成功后生效；不同于 Nacos 管理账号。\n\n')
            stream.write('用户名：`' + identity['AUTH_ADMIN_USERNAME'] + '`\n\n密码：`' + identity['AUTH_ADMIN_PASSWORD'] + '`\n')
    envfile(targets[3], identity)
    print(environment + ': authentication files generated; keys and passwords not printed. Production was not started.')

if __name__ == '__main__':
    main()
