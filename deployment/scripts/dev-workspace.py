#!/usr/bin/env python3
"""Start/stop this repository's local M2 dev processes; never deploy prod."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request

REPO=Path(__file__).resolve().parents[2]
PRIVATE=REPO/'deployment/secrets/dev'
STATE=PRIVATE/'business-processes.json'
spec=importlib.util.spec_from_file_location('runtime',Path(__file__).with_name('run-service.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)

def identity(pid):
    result=subprocess.run(['ps','-p',str(pid),'-o','stat=','-o','lstart='],capture_output=True,text=True)
    fields=result.stdout.strip().split(maxsplit=1)
    return fields[1] if result.returncode==0 and len(fields)==2 and not fields[0].startswith('Z') else ''

def descendants(pid,group):
    children=[]
    result=subprocess.run(['pgrep','-P',str(pid)],capture_output=True,text=True)
    for value in result.stdout.split():
        child=int(value)
        try:
            if os.getpgid(child)!=group:continue
        except ProcessLookupError:continue
        started=identity(child)
        if started:
            children.append({'pid':child,'identity':started})
            children.extend(descendants(child,group))
    return children

def write_state(records):
    fd=os.open(STATE,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(records,stream)

def stop(records):
    targets=[]
    for record in records:
        if identity(record['pid'])!=record['identity']:continue
        try:
            if os.getpgid(record['pid'])!=record['pid']:raise RuntimeError('Process ownership check failed; stop refused.')
        except ProcessLookupError:continue
        targets.append(record);targets.extend(descendants(record['pid'],record['pid']))
    for record in reversed(targets):
        if identity(record['pid'])==record['identity']:
            try:os.kill(record['pid'],signal.SIGTERM)
            except ProcessLookupError:pass
    deadline=time.monotonic()+12
    while any(identity(r['pid'])==r['identity'] for r in targets) and time.monotonic()<deadline:time.sleep(.2)
    for record in reversed(targets):
        if identity(record['pid'])==record['identity']:
            try:os.kill(record['pid'],signal.SIGKILL)
            except ProcessLookupError:pass
    write_state([])

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['start','stop','status']);args=parser.parse_args()
    records=json.loads(STATE.read_text()) if STATE.exists() else []
    live=[r for r in records if identity(r['pid'])==r['identity']]
    if args.action=='status':
        print('Running: '+(', '.join(r['name'] for r in live) or 'none'));return
    if args.action=='stop':stop(live);print('Stopped only recorded, identity-checked dev process groups. Docker was not stopped.');return
    if live:raise SystemExit('Recorded dev processes already exist; use status/stop before starting.')
    services=[('identity-service',8083),('booking-service',8082),('gateway',8081),('web-admin',5173)]
    for _,port in services:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0:raise SystemExit('Dev port occupied; no existing process was stopped.')
    logs=PRIVATE/'business-logs';logs.mkdir(mode=0o700,parents=True,exist_ok=True)
    for name,_ in services:
        if name=='web-admin':
            if not (REPO/'web-admin/node_modules/vite').exists():raise SystemExit('Run npm ci in web-admin first.')
        else:
            module='GraduationProject' if name=='booking-service' else name
            if not (REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar')).exists():raise SystemExit('Run root Maven verify first.')
    try:
        for name,port in services:
            if name=='web-admin':
                command=['npm','run','dev'];cwd=REPO/name;env=os.environ.copy()
                for key in list(env):
                    if any(x in key for x in ['PASSWORD','SECRET','TOKEN','API_KEY','PRIVATE_KEY','VITE_']):env.pop(key,None)
            else:
                module='GraduationProject' if name=='booking-service' else name
                command=['java','-jar',str(REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar'))];cwd=REPO
                env=runtime.service_env('dev',name,name=='identity-service')
                env.update(SPRING_CLOUD_NACOS_DISCOVERY_GROUP='XMUT_DEV_BUSINESS',SPRING_CLOUD_NACOS_DISCOVERY_IP='127.0.0.1',SERVER_PORT=str(port),SPRING_CLOUD_NACOS_DISCOVERY_GRACEFUL_SHUTDOWN_WAIT_TIME='0',MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED='true')
            fd=os.open(logs/(name+'.log'),os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            with os.fdopen(fd,'w') as log:p=subprocess.Popen(command,env=env,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            record={'name':name,'pid':p.pid,'identity':identity(p.pid),'port':port};records.append(record);write_state(records)
        waiting={port for _,port in services};deadline=time.monotonic()+100
        while waiting and time.monotonic()<deadline:
            if any(identity(r['pid'])!=r['identity'] for r in records):raise RuntimeError('Dev process exited; inspect private business-logs.')
            for port in list(waiting):
                url=f'http://127.0.0.1:{port}'+('/' if port==5173 else '/actuator/health/readiness')
                try:
                    with urllib.request.urlopen(url,timeout=2) as response:
                        if response.status==200:waiting.remove(port)
                except (urllib.error.URLError,TimeoutError):pass
            time.sleep(.3)
        if waiting:raise RuntimeError('Dev readiness timeout; inspect private business-logs.')
        print('Ready: http://127.0.0.1:5173 (dev only). Account record: deployment/secrets/dev/business-accounts.md')
    except BaseException:stop(records);raise

if __name__=='__main__':main()
