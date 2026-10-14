#!/usr/bin/env python3
"""Verify IDEA-equivalent dev startup; always stop temporary backend processes."""
import json
import os
from pathlib import Path
import shlex
import socket
import stat
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPO=Path(__file__).resolve().parents[2]
PRIVATE=REPO/'deployment/secrets/dev/idea'
SERVICES=[('identity-service',8083),('booking-service',8082),('gateway',8081),('notification-service',8085)]

# 读取实际共享 XML，而不是用另一个硬编码绝对路径掩盖 IDEA 宏或工作目录错误。
# 同时检查外层/内层项目上下文，随后用解析得到的同一组 JVM 参数实际启动服务。
def launch_settings(path,service,project):
    configuration=ET.parse(path).getroot().find('configuration')
    module='GraduationProject' if service=='booking-service' else service
    module_option=configuration.find('module')
    if module_option is None or module_option.get('name')!=module:
        raise RuntimeError('IDEA configuration module mismatch: '+service)
    options={option.get('name'):option.get('value') for option in configuration.findall('option')}
    module_dir=REPO/module
    working=(options.get('WORKING_DIRECTORY') or '').replace('$MODULE_WORKING_DIR$',str(module_dir)).replace('$PROJECT_DIR$',str(project))
    if not working or '$' in working or not Path(working).is_dir():
        raise RuntimeError('IDEA working directory invalid: '+service)
    arguments=[arg.replace('$PROJECT_DIR$',str(project)) for arg in shlex.split(options.get('VM_PARAMETERS') or '')]
    locations=[arg.removeprefix('-Dspring.config.additional-location=') for arg in arguments if arg.startswith('-Dspring.config.additional-location=')]
    if len(locations)!=1 or not locations[0].startswith('file:'):
        raise RuntimeError('IDEA private configuration location invalid: '+service)
    location=locations[0].removeprefix('file:').replace('$PROJECT_DIR$',str(project))
    if '$' in location or (Path(working)/location).resolve()!=(PRIVATE/(service+'.properties')).resolve():
        raise RuntimeError('IDEA configuration does not resolve to the repository private file: '+service)
    return Path(working).resolve(),arguments

# 以真实 XML 中的 additional-location 参数启动四个临时服务，检查就绪与网关鉴权。
# 执行前要求端口空闲，结束时清理本次创建的进程。
def main():
    settings={}
    for name,port in SERVICES:
        module='GraduationProject' if name=='booking-service' else name
        if not (REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar')).is_file():
            raise RuntimeError('Build the root Maven project with verify before running this verification.')
        properties=PRIVATE/(name+'.properties')
        if not properties.exists() or stat.S_IMODE(properties.stat().st_mode)!=0o600:
            raise RuntimeError('Run prepare-idea.py first; private configurations must have mode 600.')
        shared=REPO/'.run'/('Campus_'+name+'.run.xml')
        outer=launch_settings(shared,name,REPO)
        inner=launch_settings(shared,name,REPO/'GraduationProject')
        if outer!=inner:raise RuntimeError('IDEA configuration depends on the opened project root: '+name)
        local=REPO/'GraduationProject/.idea/runConfigurations'/('Campus_'+name.replace('-','_')+'.xml')
        if local.exists() and launch_settings(local,name,REPO/'GraduationProject')!=outer:
            raise RuntimeError('IDEA inner configuration differs from shared configuration: '+name)
        settings[name]=outer
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0:
                raise RuntimeError('Verification port occupied; no existing service was stopped.')
    print('PASS: actual shared and inner XML resolve the same private file from either IDEA project root.',flush=True)
    logs=REPO/'deployment/secrets/dev/idea-verification'
    logs.mkdir(mode=0o700,exist_ok=True)
    os.chmod(logs,0o700)
    children=[]
    try:
        for name,_ in SERVICES:
            module='GraduationProject' if name=='booking-service' else name
            fd=os.open(logs/(name+'.log'),os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            os.fchmod(fd,0o600)
            log=os.fdopen(fd,'w')
            working,arguments=settings[name]
            command=['java',*arguments,
                     '-jar',str(REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar'))]
            # 清除父进程继承的服务配置，确保验证结果来自 IDEA 的 properties 文件。
            env=os.environ.copy()
            for key in list(env):
                if key.startswith(('SPRING_','JWT_','AUTH_','DB_','IDENTITY_','NOTIFICATION_','SERVICE_CALL_','REDIS_','RABBITMQ_','NACOS_','SERVER_','IMPORT_')):
                    env.pop(key,None)
            process=subprocess.Popen(command,env=env,cwd=working,stdout=log,stderr=subprocess.STDOUT)
            children.append((process,log))
        waiting={port for _,port in SERVICES};deadline=time.monotonic()+80
        while waiting and time.monotonic()<deadline:
            if any(p.poll() is not None for p,_ in children):
                raise RuntimeError('IDEA-equivalent launch failed; inspect private idea-verification logs.')
            for port in list(waiting):
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/readiness',timeout=2) as response:
                        if response.status==200:waiting.remove(port)
                except (urllib.error.URLError,TimeoutError):pass
            time.sleep(.3)
        if waiting:raise RuntimeError('IDEA-equivalent readiness timeout.')
        print('PASS: identity, booking, notification and gateway start from private additional-location properties without service environment variables.',flush=True)
        try:
            urllib.request.urlopen('http://127.0.0.1:8081/api/v1/resources',timeout=5)
        except urllib.error.HTTPError as error:
            body=json.load(error)
            if error.code!=401 or body['code']!='UNAUTHORIZED':
                raise RuntimeError('Unexpected gateway authentication response.')
        else:raise RuntimeError('Formal gateway authentication missing.')
        print('PASS: dev/business profiles and formal authentication active; MySQL/Flyway initialization succeeded.',flush=True)
    # 无论成功或异常都回收临时进程，并检查测试端口是否释放。
    finally:
        for process,log in reversed(children):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            log.close()
        for _,port in SERVICES:
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1',port))==0:
                    raise RuntimeError('Temporary verification port not released.')
        print('PASS: temporary backend processes stopped; 8081-8083 and 8085 released.',flush=True)

if __name__=='__main__':main()
