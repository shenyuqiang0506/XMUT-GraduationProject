#!/usr/bin/env python3
"""准备 M4 分环境的服务签名密钥和通知环境文件；不轮换已有凭据，也不启动 prod。"""
import argparse
import importlib.util
import os
from pathlib import Path
import secrets

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runtime',Path(__file__).with_name('run-service.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
spec=importlib.util.spec_from_file_location('auth_init',Path(__file__).with_name('init-auth.py'))
auth_init=importlib.util.module_from_spec(spec);spec.loader.exec_module(auth_init)

def prepare(environment):
    source=runtime.load_env(ROOT/('.env.'+environment))
    if source['APP_ENV']!=environment:raise RuntimeError('环境标记不一致')
    private=ROOT/'secrets'/environment
    private.mkdir(mode=0o700,parents=True,exist_ok=True)
    key=private/'booking-service-call.key'
    if key.is_symlink():raise RuntimeError('拒绝符号链接密钥目标')
    if not key.exists():
        fd=os.open(key,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream:stream.write(secrets.token_bytes(32))
    if len(key.read_bytes())!=32:raise RuntimeError('已有服务签名密钥长度无效，不覆盖')
    os.chmod(key,0o600)
    env=private/'notification.env'
    if env.is_symlink():raise RuntimeError('拒绝符号链接环境文件')
    if not env.exists():
        port='3306' if environment=='dev' else '13306'
        values={'NOTIFICATION_DB_URL':f'jdbc:mysql://127.0.0.1:{port}/notification_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                'NOTIFICATION_DB_USERNAME':source['NOTIFICATION_DB_USER'],'NOTIFICATION_DB_PASSWORD':source['NOTIFICATION_DB_PASSWORD'],
                'RABBITMQ_HOST':'127.0.0.1','RABBITMQ_PORT':'5672' if environment=='dev' else '15673',
                'RABBITMQ_USERNAME':source['RABBITMQ_USERNAME'],'RABBITMQ_PASSWORD':source['RABBITMQ_PASSWORD'],'RABBITMQ_VHOST':source['RABBITMQ_VHOST']}
        auth_init.envfile(env,values)
    os.chmod(env,0o600)
    print(environment+': 服务签名与通知配置已准备，已有账户和密码未变；未启动 prod。')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('environment',choices=['dev','prod']);prepare(parser.parse_args().environment)
if __name__=='__main__':main()
