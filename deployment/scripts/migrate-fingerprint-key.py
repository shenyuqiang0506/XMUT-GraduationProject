#!/usr/bin/env python3
"""为已有 dev/prod 保存当前 HMAC 密钥，保持旧幂等指纹，绝不轮换 JWT 或账户密码。"""
import argparse
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import shlex

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime', Path(__file__).with_name('run-service.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

def migrate(environment):
    private = REPO / 'deployment/secrets' / environment
    config = private / 'identity.env'
    if config.is_symlink():
        raise RuntimeError('拒绝符号链接配置文件')
    values = runtime.load_env(config)
    target = private / 'password-fingerprint.key'
    if 'AUTH_PASSWORD_FINGERPRINT_KEY_PATH' in values:
        key = Path(values['AUTH_PASSWORD_FINGERPRINT_KEY_PATH'])
        if key.is_symlink() or len(key.read_bytes()) != 32:
            raise RuntimeError('已有指纹密钥无效')
        print(environment + ': 独立指纹密钥已配置，无需修改。')
        return
    # Java PKCS#8 私钥编码与 PEM 中 DER 字节相同；保存旧派生值，避免破坏旧请求回放。
    pem = Path(values['JWT_PRIVATE_KEY_PATH']).read_text()
    if not pem.startswith('-----BEGIN PRIVATE KEY-----'):
        raise RuntimeError('只支持当前项目的 PKCS#8 私钥')
    der = base64.b64decode(''.join(line for line in pem.splitlines() if not line.startswith('-----')), validate=True)
    key = hashlib.sha256(b'xmut-identity-idempotency-password-v1' + der).digest()
    if target.is_symlink():
        raise RuntimeError('拒绝符号链接密钥文件')
    if target.exists():
        if target.read_bytes() != key:
            raise RuntimeError('密钥已存在且不一致，拒绝覆盖')
    else:
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(key)
    os.chmod(target, 0o600)
    # 原子替换配置，保留全部旧值和注释；中途失败可安全重复执行。
    temporary = config.with_name('identity.env.migration')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(config.read_text().rstrip() + '\nAUTH_PASSWORD_FINGERPRINT_KEY_PATH=' + shlex.quote(str(target)) + '\n')
    os.replace(temporary, config)
    print(environment + ': 已保存兼容旧指纹的独立密钥；密钥和密码未输出。')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    migrate(parser.parse_args().environment)
