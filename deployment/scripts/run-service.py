#!/usr/bin/env python3
"""Start one business service using its own private environment files."""
import argparse
import os
from pathlib import Path
import shlex

REPO = Path(__file__).resolve().parents[2]

# 读取本项目生成的环境文件，按第一个等号拆分，保留值内的等号。
# 使用 shell 引号规则还原值，但不执行文件中的任何命令。
def load_env(path):
    values = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            values[key] = shlex.split(value)[0] if value else ''
    return values

# 按环境与服务构造最小化配置：共享验签公钥，签名私钥仅交给身份服务。
# 业务数据的 REAL/DEMO 模式与这里的 dev/prod 部署环境是不同维度。
def service_env(environment, service, bootstrap=False):
    private = REPO / 'deployment/secrets' / environment
    source = load_env(REPO / 'deployment' / ('.env.' + environment))
    if source['APP_ENV'] != environment:
        raise RuntimeError('Environment mismatch')
    env = os.environ.copy()
    # Keep credentials explicitly scoped even if the parent shell sourced another service's env.
    for key in ['JWT_PRIVATE_KEY_PATH','AUTH_PASSWORD_FINGERPRINT_KEY_PATH','AUTH_ADMIN_USERNAME','AUTH_ADMIN_PASSWORD',
                'IDENTITY_DB_URL','IDENTITY_DB_USERNAME','IDENTITY_DB_PASSWORD',
                'DB_USERNAME','DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD']:
        env.pop(key,None)
    env.update(load_env(private / 'auth.env'))
    env.update({key: source[key] for key in ['NACOS_USERNAME', 'NACOS_PASSWORD']})
    env['NACOS_SERVER_ADDR'] = '127.0.0.1:' + ('8848' if environment == 'dev' else '18848')
    if service == 'identity-service':
        env.update(load_env(private / 'identity.env'))
        if not bootstrap:
            env.pop('AUTH_ADMIN_USERNAME', None)
            env.pop('AUTH_ADMIN_PASSWORD', None)
    elif service == 'booking-service':
        env.update(load_env(private / 'backend.env'))
    elif service == 'gateway':
        # 网关只领取限流所需的 Redis 凭据，不领取数据库、签名私钥或管理员密码。
        env.update(REDIS_HOST='127.0.0.1', REDIS_PORT='6379' if environment == 'dev' else '16379',
                   REDIS_PASSWORD=source['REDIS_PASSWORD'])
    env['SPRING_PROFILES_ACTIVE'] = environment + ',business' + (',bootstrap-admin' if bootstrap else '')
    env['SERVER_ADDRESS'] = '127.0.0.1'
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    parser.add_argument('service', choices=['identity-service', 'booking-service', 'gateway', 'ai-service'])
    parser.add_argument('--bootstrap-admin', action='store_true')
    args = parser.parse_args()
    if args.bootstrap_admin and (args.environment != 'dev' or args.service != 'identity-service'):
        raise SystemExit('Bootstrap is allowed only for dev identity-service.')
    module = 'GraduationProject' if args.service == 'booking-service' else args.service
    jar = REPO / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')
    if not jar.exists():
        raise SystemExit('Build the root Maven reactor first.')
    env = service_env(args.environment, args.service, args.bootstrap_admin)
    # 以 Java 进程替换启动器，使终端信号和退出状态直接对应服务。
    os.execvpe('java', ['java', '-jar', str(jar)], env)

if __name__ == '__main__':
    main()
