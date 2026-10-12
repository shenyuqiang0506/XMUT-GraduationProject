#!/usr/bin/env python3
"""Create independent local credentials. Never overwrite existing passwords."""
import argparse
import base64
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]

# 以仅当前用户可读写的权限新建凭据文件；O_EXCL 防止覆盖已有密码。
def write_private(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(text)

# 每套环境单独生成数据库、中间件凭据，并留存本地账号记录。
# 此步骤只生成配置，不启动容器，也不会替运行中的服务修改密码。
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    env = parser.parse_args().environment
    private = ROOT / 'secrets' / env
    targets = [ROOT / f'.env.{env}', private / 'accounts.md', private / 'backend.env']
    if any(p.exists() for p in targets):
        raise SystemExit('Credentials already exist; refusing to overwrite. Rotate running services explicitly.')
    private.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(ROOT / 'secrets', 0o700)
    os.chmod(private, 0o700)
    token = lambda: secrets.token_hex(24)
    values = {'APP_ENV': env, 'MYSQL_ROOT_PASSWORD': token()}
    for service in ['IDENTITY', 'BOOKING', 'AI', 'NOTIFICATION']:
        values[f'{service}_DB_USER'] = f'{service.lower()}_{env}'
        values[f'{service}_DB_PASSWORD'] = token()
    # Nacos 管理员密码、token 签名密钥和服务端身份值用途不同，分别随机生成。
    values.update(REDIS_USERNAME='default', REDIS_PASSWORD=token(),
                  RABBITMQ_USERNAME=f'xmut_{env}', RABBITMQ_PASSWORD=token(),
                  RABBITMQ_VHOST=f'xmut_{env}', NACOS_USERNAME='nacos', NACOS_PASSWORD=token(),
                  NACOS_AUTH_TOKEN=base64.b64encode(secrets.token_bytes(48)).decode(),
                  NACOS_AUTH_IDENTITY_KEY=f'xmut_{env}', NACOS_AUTH_IDENTITY_VALUE=token())
    write_private(targets[0], '# PRIVATE: generated once; never commit or overwrite.\n' +
                  '\n'.join(f'{k}={v}' for k, v in values.items()) + '\n')
    ports = (3306, 6379, 5672, 15672, 8848, 8088) if env == 'dev' else (13306, 16379, 15673, 25672, 18848, 18088)
    rows = [('MySQL root（仅容器内管理）', 'root', values['MYSQL_ROOT_PASSWORD'])]
    rows += [(f'MySQL {s.lower()}_db', values[f'{s}_DB_USER'], values[f'{s}_DB_PASSWORD'])
             for s in ['IDENTITY', 'BOOKING', 'AI', 'NOTIFICATION']]
    rows += [('Redis', 'default', values['REDIS_PASSWORD']),
             ('RabbitMQ', values['RABBITMQ_USERNAME'], values['RABBITMQ_PASSWORD']),
             ('Nacos 管理员', 'nacos', values['NACOS_PASSWORD'])]
    report = f'# {env} 环境私密账号记录\n\n本文件包含真实密码，权限 600，禁止提交 Git。请另存到密码管理器/加密备份。\n\n'
    report += '这是初始化凭据；修改正在运行的服务密码后必须同步更新。prod 配置生成不表示已部署。\n\n'
    report += '| 服务 | 账号 | 密码 |\n| --- | --- | --- |\n'
    report += ''.join(f'| {s} | `{u}` | `{p}` |\n' for s, u, p in rows)
    report += f'\nMySQL: 127.0.0.1:{ports[0]}；Redis: 127.0.0.1:{ports[1]}；AMQP: 127.0.0.1:{ports[2]}。\n'
    report += f'\nRabbitMQ 控制台：http://127.0.0.1:{ports[3]}；vhost：`xmut_{env}`。\n'
    report += f'\nNacos 控制台：http://127.0.0.1:{ports[5]}；API：127.0.0.1:{ports[4]}。\n'
    report += '\nNacos 的 token 签名密钥、服务端身份值保存在同环境 .env 文件中。Nacos 密码需要 bootstrap-nacos.py 初始化。\n'
    write_private(targets[1], report)
    # Java 在宿主机运行，因此使用容器映射到本机的端口，而非 Docker 服务名。
    backend = {
        'SPRING_PROFILES_ACTIVE': env,
        'DB_URL': f'jdbc:mysql://127.0.0.1:{ports[0]}/booking_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
        'DB_USERNAME': values['BOOKING_DB_USER'], 'DB_PASSWORD': values['BOOKING_DB_PASSWORD'],
        'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(ports[1]), 'REDIS_PASSWORD': values['REDIS_PASSWORD'],
        'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': str(ports[2]),
        'RABBITMQ_USERNAME': values['RABBITMQ_USERNAME'], 'RABBITMQ_PASSWORD': values['RABBITMQ_PASSWORD'],
        'RABBITMQ_VHOST': values['RABBITMQ_VHOST'], 'NACOS_SERVER_ADDR': f'127.0.0.1:{ports[4]}',
        'NACOS_USERNAME': values['NACOS_USERNAME'], 'NACOS_PASSWORD': values['NACOS_PASSWORD']}
    write_private(targets[2], '# PRIVATE: source with set -a, or import into IDE. Host-process addresses.\n' +
                  ''.join(f"{k}='{v}'\n" for k, v in backend.items()))
    print(f'{env}: credentials saved (not printed):')
    for p in targets:
        print(p.relative_to(ROOT))

if __name__ == '__main__':
    main()
