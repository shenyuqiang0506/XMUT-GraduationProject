#!/usr/bin/env python3
"""准备分环境 AI 库配置；dev 可沿用已有本机模型凭据，prod 只生成未启用模板。"""
import argparse
import importlib.util
import os
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
runtime=module('runtime','run-service.py');auth=module('auth','init-auth.py')

def prepare(environment):
    source=runtime.load_env(ROOT/('.env.'+environment));private=ROOT/'secrets'/environment
    if source['APP_ENV']!=environment:raise RuntimeError('环境标记不一致')
    private.mkdir(mode=0o700,parents=True,exist_ok=True);target=private/'ai.env'
    if target.is_symlink():raise RuntimeError('拒绝符号链接环境文件')
    if not target.exists():
        port='3306' if environment=='dev' else '13306'
        values={'AI_DB_URL':f'jdbc:mysql://127.0.0.1:{port}/ai_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                'AI_DB_USERNAME':source['AI_DB_USER'],'AI_DB_PASSWORD':source['AI_DB_PASSWORD'],'AI_PROVIDER':'disabled'}
        legacy=ROOT/'.env.ai'
        if environment=='dev' and legacy.exists():
            model=runtime.load_env(legacy)
            if all(model.get(k) for k in ['AI_BASE_URL','AI_MODEL','AI_API_KEY']):
                values.update({k:model[k] for k in ['AI_BASE_URL','AI_MODEL','AI_API_KEY']});values['AI_PROVIDER']='openai'
        auth.envfile(target,values)
    os.chmod(target,0o600)
    print(environment+': AI 配置已准备，已有凭据未覆盖；未输出密钥、未启动服务。')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('environment',choices=['dev','prod'])
    prepare(parser.parse_args().environment)
