#!/usr/bin/env python3
"""Verify IDEA-equivalent dev startup; always stop temporary backend processes."""
import json
import os
from pathlib import Path
import socket
import stat
import subprocess
import time
import urllib.error
import urllib.request

REPO=Path(__file__).resolve().parents[2]
PRIVATE=REPO/'deployment/secrets/dev/idea'
SERVICES=[('identity-service',8083),('booking-service',8082),('gateway',8081)]

def main():
    for name,port in SERVICES:
        module='GraduationProject' if name=='booking-service' else name
        if not (REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar')).is_file():
            raise RuntimeError('Build the root Maven project with verify before running this verification.')
        properties=PRIVATE/(name+'.properties')
        if not properties.exists() or stat.S_IMODE(properties.stat().st_mode)!=0o600:
            raise RuntimeError('Run prepare-idea.py first; private configurations must have mode 600.')
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0:
                raise RuntimeError('Verification port occupied; no existing service was stopped.')
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
            command=['java','-Dspring.config.additional-location=file:'+str(PRIVATE/(name+'.properties')),
                     '-jar',str(REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar'))]
            env=os.environ.copy()
            for key in list(env):
                if key.startswith(('SPRING_','JWT_','AUTH_','DB_','IDENTITY_','REDIS_','RABBITMQ_','NACOS_','SERVER_','IMPORT_')):
                    env.pop(key,None)
            process=subprocess.Popen(command,env=env,cwd=REPO,stdout=log,stderr=subprocess.STDOUT)
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
        print('PASS: identity, booking and gateway start from private additional-location properties without service environment variables.',flush=True)
        try:
            urllib.request.urlopen('http://127.0.0.1:8081/api/v1/resources',timeout=5)
        except urllib.error.HTTPError as error:
            body=json.load(error)
            if error.code!=401 or body['code']!='UNAUTHORIZED':
                raise RuntimeError('Unexpected gateway authentication response.')
        else:raise RuntimeError('Formal gateway authentication missing.')
        print('PASS: dev/business profiles and formal authentication active; MySQL/Flyway initialization succeeded.',flush=True)
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
        print('PASS: temporary backend processes stopped; 8081-8083 released.',flush=True)

if __name__=='__main__':main()
