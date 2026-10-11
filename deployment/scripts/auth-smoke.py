#!/usr/bin/env python3
"""Exercise business authentication against dev MySQL/Redis/Nacos and Gateway."""
import base64
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('business_runtime', REPO / 'deployment/scripts/run-service.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
PRIVATE = REPO / 'deployment/secrets/dev'


def request(url, method='GET', payload=None, token=None, trace=None, key=None, extra=None):
    headers = dict(extra or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if trace:
        headers['X-Trace-Id'] = trace
    if key:
        headers['Idempotency-Key'] = key
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(payload).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = json.load(response)
        return response.status, body, dict(response.headers)


def claims(token):
    segment = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4)))


def signed(private, payload, alg='RS256'):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    data = encode({'alg': alg, 'typ': 'JWT'}) + b'.' + encode(payload)
    signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', private], input=data, capture_output=True, check=True).stdout
    return (data + b'.' + base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()


def sql(query):
    result = subprocess.run([str(REPO / 'deployment/scripts/compose.sh'), 'dev', 'exec', '-T', 'mysql',
                             'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B identity_db'],
                            input=query.encode(), capture_output=True, check=True)
    return result.stdout.decode().strip()


def main():
    run_id = 'auth-' + uuid.uuid4().hex[:12]
    group = 'business-' + uuid.uuid4().hex[:12]
    admin_env = runtime.load_env(PRIVATE / 'identity.env')
    password = uuid.uuid4().hex + 'Aa1!'
    users = [run_id + '_student', run_id + '_manager']
    ports = [('identity-service', 8083), ('booking-service', 8082), ('gateway', 8081)]
    for _, port in ports:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise SystemExit('Business probe port occupied; no existing service was stopped.')
    logs = PRIVATE / 'auth-test-logs'
    logs.mkdir(mode=0o700, exist_ok=True)
    children = []
    checks = []
    def record(name):
        checks.append({'check': name, 'result': 'PASS'})
        print('PASS:', name, flush=True)
    base = 'http://127.0.0.1:8081/api/v1'
    def call(path, method='GET', body=None, token=None, key=None, extra=None):
        return request(base + path, method, body, token, run_id + '-request', key, extra)
    def login(name, device, pwd=password):
        return call('/auth/login', 'POST', {'username': name, 'password': pwd, 'deviceId': device, 'deviceType': 'WEB'})
    try:
        for name, port in ports:
            env = runtime.service_env('dev', name, name == 'identity-service')
            env.update(SERVER_PORT=str(port), MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED='true',
                       SPRING_CLOUD_NACOS_DISCOVERY_GROUP=group, SPRING_CLOUD_NACOS_DISCOVERY_IP='127.0.0.1',
                       SPRING_CLOUD_NACOS_DISCOVERY_GRACEFUL_SHUTDOWN_WAIT_TIME='0')
            module = 'GraduationProject' if name == 'booking-service' else name
            jar = REPO / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')
            fd = os.open(logs / (name + '.log'), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            log = os.fdopen(fd, 'w')
            process = subprocess.Popen(['java', '-jar', str(jar)], env=env, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
            children.append((name, process, log))
        waiting = {port for _, port in ports}
        deadline = time.monotonic() + 100
        while waiting and time.monotonic() < deadline:
            if any(process.poll() is not None for _, process, _ in children):
                raise RuntimeError('Business service failed to start; see private auth-test-logs.')
            for port in list(waiting):
                try:
                    status, _, _ = request(f'http://127.0.0.1:{port}/actuator/health/readiness')
                    if status == 200:
                        waiting.remove(port)
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                    pass
            time.sleep(.3)
        assert not waiting, 'Business startup timed out'
        record('Identity migration and dev-only administrator bootstrap; all three services ready')
        deadline = time.monotonic() + 25
        while True:
            status, body, _ = login(admin_env['AUTH_ADMIN_USERNAME'], run_id + '-admin', admin_env['AUTH_ADMIN_PASSWORD'])
            if status == 200 or time.monotonic() > deadline:
                break
            time.sleep(.5)
        assert status == 200, f'Administrator login failed: HTTP {status}, code={body.get("code")}'
        admin = body['data']['accessToken']
        assert claims(admin)['iss'].endswith(':dev:identity') and claims(admin)['aud'] in ['xmut-dev-api', ['xmut-dev-api']]
        status, body, headers = call('/users/me', token=admin)
        assert status == 200 and body['data']['roles'] == ['SYSTEM_ADMIN']
        assert body['traceId'] == headers['X-Trace-Id'] == run_id + '-request'
        record('Gateway login, RS256 claims, authoritative administrator identity and traceId')
        status, body, _ = call('/users/me', extra={'X-User-Id': '1', 'X-Roles': 'SYSTEM_ADMIN'})
        assert status == 401 and body['code'] == 'UNAUTHORIZED'
        record('Unauthenticated and forged identity headers rejected')
        records = []
        for name, role in zip(users, ['STUDENT', 'RESOURCE_ADMIN']):
            create = {'username': name, 'initialPassword': password, 'displayName': '自动测试账号', 'roleCodes': [role]}
            key = run_id + '-' + role.lower()
            status, body, _ = call('/users', 'POST', create, admin, key)
            assert status == 201
            user = body['data']
            assert 'password' not in json.dumps(user).lower()
            records.append(user)
            status, replay, headers = call('/users', 'POST', create, admin, key)
            assert status == 201 and replay['data'] == user and headers['Idempotency-Replayed'] == 'true'
            create['displayName'] = '不同请求'
            status, body, _ = call('/users', 'POST', create, admin, key)
            assert status == 409 and body['code'] == 'IDEMPOTENCY_KEY_REUSED'
            create['displayName']='自动测试账号'
            create['initialPassword']=password+'x'
            status,body,_=call('/users','POST',create,admin,key)
            assert status==409 and body['code']=='IDEMPOTENCY_KEY_REUSED'
        record('Account creation is authorized, idempotent and rejects changed payloads')
        status, body, _ = login(users[0], run_id + '-student', 'incorrect-password')
        assert status == 401 and body['code'] == 'UNAUTHORIZED'
        status, body, _ = login(users[0], run_id + '-student')
        assert status == 200
        student_tokens = body['data']
        student = student_tokens['accessToken']
        status, body, _ = call('/users', token=student)
        assert status == 403 and body['code'] == 'FORBIDDEN'
        forged = claims(student)
        forged['roles'] = ['SYSTEM_ADMIN']
        status, body, _ = call('/users', token=signed(admin_env['JWT_PRIVATE_KEY_PATH'], forged))
        assert status == 403
        record('Wrong password rejected; database roles prevent student access and role-claim spoofing')
        for change in [{'aud': ['other-api']}, {'iss': 'urn:other:identity'}, {'exp': int(time.time()) - 1}, {'sid': '0'}, {'sub':'9999999999999999999'}, {'auth_version':0.5}]:
            payload = dict(claims(student), **change)
            status, body, _ = call('/users/me', token=signed(admin_env['JWT_PRIVATE_KEY_PATH'], payload))
            assert status == 401 and body['code'] == 'UNAUTHORIZED'
        status, _, _ = call('/users/me', token=signed(admin_env['JWT_PRIVATE_KEY_PATH'], claims(student), 'HS256'))
        assert status == 401
        record('Wrong audience/issuer, expired token, invalid session claim and non-RS256 algorithm rejected')
        status, body, _ = request('http://127.0.0.1:8082/actuator/info', token=student, trace=run_id + '-downstream')
        assert status == 200
        record('Booking verifies signature and resolves current session through identity service')
        old_refresh = student_tokens['refreshToken']
        status, body, _ = call('/auth/refresh', 'POST', {'refreshToken': old_refresh, 'deviceId': run_id + '-wrong-device'})
        assert status == 401
        status, body, _ = call('/auth/refresh', 'POST', {'refreshToken': old_refresh, 'deviceId': run_id + '-student'})
        assert status == 200 and body['data']['refreshToken'] != old_refresh
        renewed = body['data']
        status, body, _ = call('/auth/refresh', 'POST', {'refreshToken': old_refresh, 'deviceId': run_id + '-student'})
        assert status == 401 and body['code'] == 'REFRESH_TOKEN_REUSED'
        status, body, _ = call('/users/me', token=renewed['accessToken'])
        assert status == 401 and body['code'] == 'SESSION_REVOKED'
        status, body, _ = request('http://127.0.0.1:8082/actuator/info', token=renewed['accessToken'], trace=run_id + '-revoked')
        assert status == 401
        record('Refresh device binding, rotation, replay revocation and downstream enforcement')
        status, body, _ = login(users[0], run_id + '-parallel')
        assert status == 200
        refresh = body['data']['refreshToken']
        payload = {'refreshToken': refresh, 'deviceId': run_id + '-parallel'}
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: call('/auth/refresh', 'POST', payload), range(2)))
        assert sorted(result[0] for result in results) == [200, 401]
        record('Concurrent refresh serializes; one rotation succeeds and replay revokes the session')
        status, body, _ = login(users[1], run_id + '-manager')
        assert status == 200
        manager = body['data']['accessToken']
        target = records[1]['id']
        status, body, _ = call('/users/' + target + '/roles', 'PUT', {'roleCodes': ['STUDENT'], 'expectedVersion': 0}, admin, run_id + '-roles')
        assert status == 200 and body['data']['authVersion'] == 1
        status, body, _ = call('/users/me', token=manager)
        assert status == 401
        status, body, _ = call('/users/' + target + '/status', 'PATCH', {'status': 'DISABLED', 'expectedVersion': 0}, admin, run_id + '-stale')
        assert status == 409 and body['code'] == 'VERSION_CONFLICT'
        status, body, _ = call('/users/' + target + '/status', 'PATCH', {'status': 'DISABLED', 'expectedVersion': 1}, admin, run_id + '-disable')
        assert status == 200
        status, body, _ = login(users[1], run_id + '-disabled')
        assert status == 401
        record('Role changes revoke sessions; stale version rejected; disabled user cannot log in')
        status, body, _ = login(users[0], run_id + '-logout')
        assert status == 200
        logout_tokens = body['data']
        status, body, _ = call('/auth/logout', 'POST', {}, logout_tokens['accessToken'])
        assert status == 200
        status, body, _ = call('/users/me', token=logout_tokens['accessToken'])
        assert status == 401
        status, body, _ = call('/auth/refresh', 'POST', {'refreshToken': logout_tokens['refreshToken'], 'deviceId': run_id + '-logout'})
        assert status == 401
        record('Logout immediately revokes both access and refresh credentials')
        count = sql("SELECT COUNT(*) FROM audit_log WHERE trace_id LIKE '" + run_id + "%';")
        assert int(count) >= 5
        hashes = sql("SELECT u.password_hash,s.refresh_token_hash FROM `user` u JOIN user_session s ON s.user_id=u.id WHERE u.username='" + users[0] + "';")
        assert password not in hashes and old_refresh not in hashes and all(row.startswith('$2') for row in hashes.splitlines())
        record('MySQL persists BCrypt/password and refresh hashes plus audit records, without plain credentials')
        status, body, _ = call('/users', token=admin)
        assert status == 200 and int(body['data']['total']) >= 3
        status, body, _ = call('/users?page=0', token=admin)
        assert status == 400
        status, body, _ = call('/auth/login', 'POST', {})
        assert status == 400 and body['code'] == 'VALIDATION_ERROR'
        record('Pagination and request validation enforce server-side limits')
        # Stop identity: booking must reject requests instead of trusting the previously valid JWT.
        status, body, _ = login(users[0], run_id + '-unavailable')
        assert status == 200
        live = body['data']['accessToken']
        identity = next(p for name,p,_ in children if name == 'identity-service')
        identity.terminate(); identity.wait(timeout=15)
        status, body, _ = request('http://127.0.0.1:8082/actuator/info', token=live, trace=run_id + '-identity-down')
        assert status == 503 and body['code'] == 'SERVICE_UNAVAILABLE'
        record('Identity outage produces controlled 503; no default-allow session fallback')
        # Only this test's identity process uses an unreachable Redis port; Docker Redis remains running.
        redis_env=runtime.service_env('dev','identity-service')
        redis_env.update(REDIS_PORT='1',SERVER_PORT='8083',MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED='true',SPRING_CLOUD_NACOS_DISCOVERY_GROUP=group,
                         SPRING_CLOUD_NACOS_DISCOVERY_IP='127.0.0.1',SPRING_CLOUD_NACOS_DISCOVERY_GRACEFUL_SHUTDOWN_WAIT_TIME='0')
        fd=os.open(logs/'identity-redis-unavailable.log',os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        redis_log=os.fdopen(fd,'w')
        redis_process=subprocess.Popen(['java','-jar',str(REPO/'identity-service/target/identity-service-0.0.1-SNAPSHOT.jar')],env=redis_env,cwd=REPO,stdout=redis_log,stderr=subprocess.STDOUT)
        children.append(('identity-redis-unavailable',redis_process,redis_log))
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            assert redis_process.poll() is None, 'Redis-isolated identity failed to start'
            try:
                status,_,_=request('http://127.0.0.1:8083/actuator/health/liveness')
                if status==200:break
            except (urllib.error.URLError,TimeoutError,json.JSONDecodeError):pass
            time.sleep(.3)
        status,body,_=request('http://127.0.0.1:8083/users/me',token=live)
        assert status==200 and body['data']['username']==users[0]
        status,body,_=request('http://127.0.0.1:8083/auth/login','POST',{'username':users[0],'password':password,'deviceId':run_id+'-redis-down','deviceType':'WEB'})
        assert status==503 and body['code']=='SERVICE_UNAVAILABLE'
        status,body,_=request('http://127.0.0.1:8083/auth/logout','POST',{},live)
        assert status==200
        status,body,_=request('http://127.0.0.1:8083/users/me',token=live)
        assert status==401
        record('Redis outage keeps authoritative DB session checks and logout revocation; new login fails closed when throttling is unavailable')
        destination = REPO / '开发文档/docs/verification/2026-10-11/m2'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'authentication.json').write_text(json.dumps({'date': '2026-10-11', 'mode': 'dev-business-real-infrastructure',
            'checks': checks, 'passed': len(checks), 'failed': 0, 'scope': 'Formal identity APIs; catalog and reservation acceptance are separate'}, ensure_ascii=False, indent=2) + '\n')
    finally:
        for _, process, log in reversed(children):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
            log.close()
        # Remove only this run's disposable users and associated rows; keep bootstrap admin and migrations.
        quoted = ','.join("'" + user + "'" for user in users)
        cleanup = "SET @test_ids=1; DELETE t FROM used_refresh_token t JOIN user_session s ON s.id=t.session_id JOIN `user` u ON u.id=s.user_id WHERE u.username IN (" + quoted + ") OR s.device_id LIKE '" + run_id + "%';"
        cleanup += "DELETE s FROM user_session s JOIN `user` u ON u.id=s.user_id WHERE u.username IN (" + quoted + ") OR s.device_id LIKE '" + run_id + "%';"
        cleanup += "DELETE r FROM user_role r JOIN `user` u ON u.id=r.user_id WHERE u.username IN (" + quoted + ");"
        cleanup += "DELETE FROM idempotency_record WHERE request_key LIKE '" + run_id + "%';DELETE FROM audit_log WHERE trace_id LIKE '" + run_id + "%';"
        cleanup += "DELETE FROM `user` WHERE username IN (" + quoted + ");"
        try: sql(cleanup)
        except (subprocess.CalledProcessError, OSError): print('Disposable-user cleanup unavailable; see private run prefix: ' + run_id, flush=True)

if __name__ == '__main__':
    try: main()
    except (AssertionError, RuntimeError) as failure:
        import traceback
        location = traceback.extract_tb(failure.__traceback__)[-1].lineno
        raise SystemExit(str(failure) or f'Authentication verification failed at script line {location}; see private logs.')
