#!/usr/bin/env python3
"""Isolated dev business service lifecycle for real API/browser acceptance."""
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import uuid

REPO=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('auth_probe',REPO/'deployment/scripts/auth-smoke.py')
auth=importlib.util.module_from_spec(spec);spec.loader.exec_module(auth)
runtime=auth.runtime
PRIVATE=auth.PRIVATE

def sql(query,database='booking_db'):
    result=subprocess.run([str(REPO/'deployment/scripts/compose.sh'),'dev','exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B '+database],input=query.encode(),capture_output=True,check=True)
    return result.stdout.decode().strip()

class Stack:
    def __init__(self,label):
        self.group='business-'+uuid.uuid4().hex[:12];self.children=[];self.label=label
    def __enter__(self):
        for port in [8081,8082,8083]:
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1',port))==0: raise RuntimeError('Test port occupied; no existing service was stopped.')
        try:
            for name,port in [('identity-service',8083),('booking-service',8082),('gateway',8081)]: self.start(name,port)
            self.ready();return self
        except BaseException:
            self.__exit__(None,None,None);raise
    def start(self,name,port,overrides=None):
        env=runtime.service_env('dev',name,name=='identity-service')
        env.update(SERVER_PORT=str(port),MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED='true',
            SPRING_CLOUD_NACOS_DISCOVERY_GROUP=self.group,SPRING_CLOUD_NACOS_DISCOVERY_IP='127.0.0.1',SPRING_CLOUD_NACOS_DISCOVERY_GRACEFUL_SHUTDOWN_WAIT_TIME='0')
        env.update(overrides or {})
        logs=PRIVATE/(self.label+'-logs');logs.mkdir(mode=0o700,exist_ok=True)
        fd=os.open(logs/(name+'.log'),os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600);log=os.fdopen(fd,'w')
        module='GraduationProject' if name=='booking-service' else name
        child=subprocess.Popen(['java','-jar',str(REPO/module/'target'/(module+'-0.0.1-SNAPSHOT.jar'))],env=env,cwd=REPO,stdout=log,stderr=subprocess.STDOUT)
        self.children.append((name,child,log))
    def ready(self):
        waiting={8081,8082,8083};deadline=time.monotonic()+100
        while waiting and time.monotonic()<deadline:
            if any(p.poll() is not None for _,p,_ in self.children): raise RuntimeError('Business service failed to start; inspect private test logs.')
            for port in list(waiting):
                try:
                    if auth.request(f'http://127.0.0.1:{port}/actuator/health/readiness')[0]==200:waiting.remove(port)
                except (urllib.error.URLError,TimeoutError,ValueError): pass
            time.sleep(.3)
        if waiting: raise RuntimeError('Business readiness timeout.')
        time.sleep(2)
    def __exit__(self,*_):
        for _,p,log in reversed(self.children):
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=15)
                except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
            log.close()


def cleanup_identity(prefix):
    safe="'"+prefix+"%'"
    sql("DELETE t FROM used_refresh_token t JOIN user_session s ON s.id=t.session_id JOIN `user` u ON u.id=s.user_id WHERE u.username LIKE "+safe+" OR s.device_id LIKE "+safe+";"
        "DELETE s FROM user_session s JOIN `user` u ON u.id=s.user_id WHERE u.username LIKE "+safe+" OR s.device_id LIKE "+safe+";"
        "DELETE r FROM user_role r JOIN `user` u ON u.id=r.user_id WHERE u.username LIKE "+safe+";"
        "DELETE FROM idempotency_record WHERE request_key LIKE "+safe+";DELETE FROM audit_log WHERE trace_id LIKE "+safe+";"
        "DELETE FROM `user` WHERE username LIKE "+safe+";",'identity_db')
