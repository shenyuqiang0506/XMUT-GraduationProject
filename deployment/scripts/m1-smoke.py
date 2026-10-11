#!/usr/bin/env python3
"""Verify real Nacos/Gateway/Feign and Spring AI with a stub or configured provider."""
import argparse
import base64
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = Path(__file__).resolve().parents[2]
PRIVATE = REPO / 'deployment/secrets/dev'


def load_env(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            result[key] = shlex.split(value)[0] if value else ''
    return result


class ModelStub(BaseHTTPRequestHandler):
    calls = []
    def log_message(self, *_):
        pass
    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        type(self).calls.append(payload)
        texts = ' '.join(str(m.get('content', '')) for m in payload['messages'] if m['role'] == 'user')
        if 'STUB_TIMEOUT' in texts:
            time.sleep(2)
        if 'STUB_FAIL' in texts:
            self.send_json(503, {'error': {'message': 'Simulated provider unavailable', 'type': 'server_error', 'code': 'unavailable'}})
            return
        has_result = any(m['role'] == 'tool' for m in payload['messages'])
        if 'STUB_NO_TOOL' in texts or (has_result and 'STUB_LOOP' not in texts):
            message = {'role': 'assistant', 'content': '技术验证演示场地容量20，状态UNKNOWN，需核实；未创建预约。'}
            reason = 'stop'
        else:
            resource_id = 'invalid-resource' if 'STUB_INVALID_RESOURCE' in texts else 'demo-venue-1'
            message = {'role': 'assistant', 'content': None, 'tool_calls': [
                {'id': 'call_' + uuid.uuid4().hex, 'type': 'function', 'function': {
                    'name': 'getResourceDetails', 'arguments': json.dumps({'resourceId': resource_id})}}]}
            reason = 'tool_calls'
        self.send_json(200, {'id': 'chatcmpl-m1', 'object': 'chat.completion', 'created': int(time.time()),
                            'model': 'm1-local-stub', 'choices': [{'index': 0, 'message': message, 'finish_reason': reason}],
                            'usage': {'prompt_tokens': 10, 'completion_tokens': 10, 'total_tokens': 20}})
    def send_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


def http(url, credentials=None, trace=None, payload=None):
    headers = {}
    if credentials:
        headers['Authorization'] = 'Basic ' + base64.b64encode(credentials.encode()).decode()
    if trace is not None:
        headers['X-Trace-Id'] = trace
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=65)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response), dict(response.headers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', action='store_true', help='Use deployment/.env.ai; may incur provider usage charges')
    args = parser.parse_args()
    env = os.environ.copy()
    env.update(load_env(PRIVATE / 'backend.env'))
    env.update(load_env(PRIVATE / 'm1.env'))
    mode = 'real-provider' if args.real_model else 'local-protocol-stub'
    if args.real_model:
        env.update(load_env(REPO / 'deployment/.env.ai'))
        for key in ['AI_BASE_URL', 'AI_MODEL', 'AI_API_KEY']:
            if not env.get(key):
                raise SystemExit(f'Missing {key} in local AI configuration')
    # Isolate registrations from all existing dev business/test instances.
    env['SPRING_CLOUD_NACOS_DISCOVERY_GROUP'] = 'm1-' + uuid.uuid4().hex[:12]
    env['SPRING_CLOUD_NACOS_DISCOVERY_IP'] = '127.0.0.1'
    env['SERVER_ADDRESS'] = '127.0.0.1'
    env['SPRING_CLOUD_NACOS_DISCOVERY_GRACEFUL_SHUTDOWN_WAIT_TIME'] = '0'
    env['MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED'] = 'true'
    credentials = env['M1_SMOKE_USERNAME'] + ':' + env['M1_SMOKE_PASSWORD']
    modules = [('identity-service', 8083, 'identity-service'),
               ('GraduationProject', 8082, 'GraduationProject'),
               ('ai-service', 8084, 'ai-service'), ('gateway', 8081, 'gateway')]
    for _, port, _ in modules:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise SystemExit(f'Port {port} is already occupied; no existing service was stopped.')
    stub = None
    processes = []
    output = PRIVATE / ('m1-real-logs' if args.real_model else 'm1-stub-logs')
    output.mkdir(mode=0o700, exist_ok=True)
    records = []
    run_id = 'm1-' + uuid.uuid4().hex[:12]
    def record(name):
        records.append({'check': name, 'result': 'PASS'})
        print('PASS:', name, flush=True)
    try:
        if not args.real_model:
            ModelStub.calls.clear()
            stub = ThreadingHTTPServer(('127.0.0.1', 0), ModelStub)
            stub.daemon_threads = True
            threading.Thread(target=stub.serve_forever, daemon=True).start()
            env.update(AI_BASE_URL=f'http://127.0.0.1:{stub.server_port}', AI_MODEL='m1-local-stub',
                       AI_API_KEY='local-stub-key', AI_TIMEOUT='1s')
        for module, port, artifact in modules:
            jar = REPO / module / 'target' / f'{artifact}-0.0.1-SNAPSHOT.jar'
            if not jar.exists():
                raise SystemExit('Build all modules with mvnw verify before running the M1 verifier.')
            child_env = dict(env, SERVER_PORT=str(port), SPRING_PROFILES_ACTIVE='dev,m1' + (',ai' if module == 'ai-service' else ''))
            log_path = output / f'{artifact}.log'
            fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            log = os.fdopen(fd, 'w')
            process = subprocess.Popen(['java', '-jar', str(jar)], cwd=REPO, env=child_env,
                                       stdout=log, stderr=subprocess.STDOUT)
            processes.append((artifact, process, log))
        deadline = time.monotonic() + 100
        waiting = {p for _, p, _ in modules}
        while waiting and time.monotonic() < deadline:
            if any(process.poll() is not None for _, process, _ in processes):
                raise RuntimeError('A probe service failed to start; see private logs (never commit them).')
            for port in list(waiting):
                try:
                    status, _, _ = http(f'http://127.0.0.1:{port}/actuator/health/liveness')
                    if status == 200:
                        waiting.remove(port)
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                    pass
            time.sleep(.3)
        if waiting:
            raise RuntimeError('Probe startup timed out; see private logs.')
        record('All four independent services started with dev Nacos configuration')
        base = 'http://127.0.0.1:8081/api/v1/m1/'
        trace = run_id + '-chain'
        deadline = time.monotonic() + 20
        while True:
            status, body, headers = http(base + 'booking/status', credentials, trace)
            if status == 200 or time.monotonic() > deadline:
                break
            time.sleep(.5)
        assert status == 200 and body['code'] == 'OK', f'Gateway/Feign verification failed: HTTP {status}, code={body.get("code")}'
        assert body['data']['service'] == 'booking-service'
        assert body['data']['identity']['service'] == 'identity-service'
        assert body['traceId'] == trace == body['data']['identityTraceId'] == headers['X-Trace-Id']
        record('Gateway lb route -> booking -> Feign/LoadBalancer -> identity; traceId preserved')
        status, body, headers = http(base + 'booking/status', trace=run_id + '-auth')
        assert status == 401 and body['code'] == 'UNAUTHORIZED' and body['traceId'] == headers['X-Trace-Id']
        record('Unauthenticated gateway request rejected with traced JSON response')
        status, body, headers = http(base + 'booking/status', 'm1_dev:incorrect-password', run_id + '-wrong-auth')
        assert status == 401 and body['code'] == 'UNAUTHORIZED' and body['traceId'] == headers['X-Trace-Id']
        for port in [8082, 8083, 8084]:
            path = 'query' if port == 8084 else 'status'
            status, body, headers = http(f'http://127.0.0.1:{port}/internal/m1/{path}', trace=run_id + '-direct-auth')
            assert status == 401 and body['code'] == 'UNAUTHORIZED' and body['traceId'] == headers['X-Trace-Id']
        record('Wrong gateway password and direct unauthenticated service requests rejected')
        status, body, headers = http(base + 'booking/status', credentials, 'bad!')
        assert status == 200 and body['traceId'] != 'bad!' and body['traceId'] == body['data']['identityTraceId']
        record('Invalid incoming traceId replaced and consistently propagated')
        status, body, _ = http(base + 'booking/resources/not-allowed', credentials, run_id + '-missing')
        assert status == 404 and body['code'] == 'NOT_FOUND'
        record('Unknown DEMO resource rejected')
        status, body, _ = http(base + 'ai/query', credentials, run_id + '-validation', {'text': ''})
        assert status == 400 and body['code'] == 'VALIDATION_ERROR'
        record('AI request validation rejects empty text')
        trace = run_id + '-tool'
        status, body, headers = http(base + 'ai/query', credentials, trace,
                {'text': '调用只读工具查询 demo-venue-1 的名称、容量和状态；UNKNOWN 需核实，请勿提交预约。'})
        assert status == 200 and body['code'] == 'OK', 'AI tool round-trip did not complete'
        resources = body['data']['resources']
        assert resources[0]['resourceId'] == 'demo-venue-1' and resources[0]['capacity'] == 20
        assert resources[0]['dataMode'] == 'DEMO' and resources[0]['availability'] == 'UNKNOWN'
        assert body['traceId'] == trace == headers['X-Trace-Id']
        assert all(t == trace for t in body['data']['toolTraceIds'])
        record('Spring AI -> model -> read-only tool -> Feign -> booking -> model final answer (' + mode + ')')
        if not args.real_model:
            assert len(ModelStub.calls) == 2
            assert ModelStub.calls[0]['tools'][0]['function']['name'] == 'getResourceDetails'
            assert any(m['role'] == 'tool' and 'UNKNOWN' in m['content'] for m in ModelStub.calls[1]['messages'])
            record('OpenAI-compatible HTTP tool-call protocol and returned tool data verified')
            for marker in ['STUB_FAIL', 'STUB_TIMEOUT', 'STUB_NO_TOOL', 'STUB_INVALID_RESOURCE', 'STUB_LOOP']:
                before = len(ModelStub.calls)
                status, body, _ = http(base + 'ai/query', credentials, run_id + '-' + marker.lower(), {'text': marker})
                assert status == 503 and body['code'] == 'AI_UNAVAILABLE', f'{marker}: HTTP {status}, code={body.get("code")}, calls={len(ModelStub.calls) - before}'
                assert len(ModelStub.calls) - before <= 3, 'Unbounded model/tool loop'
                record('Controlled AI failure: ' + marker)
        # Stop only the child identity process this verifier started.
        identity_process = next(p for name, p, _ in processes if name == 'identity-service')
        identity_process.terminate()
        identity_process.wait(timeout=15)
        status, body, _ = http(base + 'booking/status', credentials, run_id + '-dependency')
        assert status == 503 and body['code'] == 'SERVICE_UNAVAILABLE'
        record('Unavailable downstream identity mapped to controlled 503')
        summary = {'date': '2026-10-11', 'mode': mode, 'model': env['AI_MODEL'],
                   'checks': records, 'passed': len(records), 'failed': 0,
                   'scope': 'M1 DEMO probes; not business authentication or booking acceptance'}
        destination = REPO / '开发文档/docs/verification/2026-10-11/m1'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / f'{mode}.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    finally:
        for _, process, log in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            log.close()
        if stub:
            stub.shutdown()
            stub.server_close()


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, RuntimeError) as error:
        raise SystemExit(str(error) or 'M1 verification failed; see private logs.')
