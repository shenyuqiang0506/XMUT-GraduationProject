#!/usr/bin/env python3
"""后端可靠性验收：dev 实际 MySQL/Redis/Nacos，临时服务和测试数据在退出时清理。"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import time
import urllib.error
import uuid

spec = importlib.util.spec_from_file_location('support', Path(__file__).with_name('business-test-support.py'))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
BASE = 'http://127.0.0.1:8081/api/v1'

class ReliabilityStack(s.Stack):
    def start(self, name, port, overrides=None):
        # 限流缩短窗口并降低额度，只作用于本次独立 audience 的临时服务。
        values = dict(JWT_AUDIENCE='campus-dev-reliability-' + self.group,
                      RECORD_CLEANUP_INITIAL_DELAY_MS='1000', RECORD_CLEANUP_DELAY_MS='1000')
        if name == 'gateway':
            values.update(BUSINESS_RATE_GENERAL='8', BUSINESS_RATE_SEARCH='2', BUSINESS_RATE_IMPORT='2',
                          BUSINESS_RATE_WINDOW_MS='3000', GATEWAY_MAX_REQUEST_BYTES='1024')
        values.update(overrides or {})
        super().start(name, port, values)

def main():
    prefix = 'rely-' + uuid.uuid4().hex[:10]
    password = uuid.uuid4().hex + 'Aa1!'
    private = s.runtime.load_env(s.PRIVATE / 'identity.env')
    checks = []
    def record(name):
        checks.append({'check': name, 'result': 'PASS'})
        print('PASS:', name, flush=True)
    def call(path, method='GET', body=None, token=None, key=None, extra=None):
        return s.auth.request(BASE + path, method, body, token, prefix + '-request', key, extra)
    def login(name, device, pwd=password):
        status, body, _ = call('/auth/login', 'POST',
                {'username': name, 'password': pwd, 'deviceId': prefix + '-' + device, 'deviceType': 'WEB'})
        assert status == 200, (status, body['code'])
        return body['data']
    def user(admin, name, key):
        payload = {'username': name, 'displayName': '可靠性验收临时账号',
                   'initialPassword': password, 'roleCodes': ['STUDENT']}
        return payload, call('/users', 'POST', payload, admin, key)
    try:
        with ReliabilityStack('backend-reliability-test') as stack:
            admin = login(private['AUTH_ADMIN_USERNAME'], 'admin', private['AUTH_ADMIN_PASSWORD'])['accessToken']
            key = prefix + '-create-user'
            payload, first = user(admin, prefix + '-student', key)
            assert first[0] == 201
            uid = first[1]['data']['id']
            assert call('/users', 'POST', payload, admin, key)[2]['Idempotency-Replayed'] == 'true'
            changed = dict(payload, username=prefix + '-student2')
            assert call('/users', 'POST', changed, admin, key)[0] == 409
            s.sql("UPDATE idempotency_record SET expires_at='2000-01-01' WHERE request_key='" + key + "';", 'identity_db')
            second = call('/users', 'POST', changed, admin, key)
            assert second[0] == 201 and second[2]['Idempotency-Replayed'] == 'false'
            record('身份幂等记录有效期内回放并拒绝不同内容，过期后允许新请求')

            time.sleep(3.1)
            group_key = prefix + '-group'
            first = call('/admin/approval-groups', 'POST', {'name': prefix + '-A'}, admin, group_key)
            assert first[0] == 201
            assert call('/admin/approval-groups', 'POST', {'name': prefix + '-A'}, admin, group_key)[2]['Idempotency-Replayed'] == 'true'
            assert call('/admin/approval-groups', 'POST', {'name': prefix + '-B'}, admin, group_key)[0] == 409
            s.sql("UPDATE idempotency_record SET expires_at='2000-01-01' WHERE request_key='" + group_key + "';")
            second = call('/admin/approval-groups', 'POST', {'name': prefix + '-B'}, admin, group_key)
            assert second[0] == 201 and second[2]['Idempotency-Replayed'] == 'false'
            record('预约库幂等有效期和不同内容冲突行为一致')

            student_session = login(prefix + '-student', 'student')
            student = student_session['accessToken']
            # 构造仅属于临时用户的过期/未过期会话和刷新摘要，不触碰其他会话。
            old_hash = hashlib.sha256((prefix + '-expired').encode()).hexdigest()
            live_hash = hashlib.sha256((prefix + '-live').encode()).hexdigest()
            s.sql("INSERT INTO user_session(user_id,device_id,device_type,refresh_token_hash,expires_at) VALUES (" + uid + ", '" + prefix + "-expired','WEB','" + old_hash + "','2000-01-01');"
                  "INSERT INTO used_refresh_token(token_hash,session_id,expires_at) SELECT '" + old_hash + "',id,expires_at FROM user_session WHERE device_id='" + prefix + "-expired';"
                  "INSERT INTO used_refresh_token(token_hash,session_id,expires_at) SELECT '" + live_hash + "',id,expires_at FROM user_session WHERE id=" + student_session['sessionId'] + ";", 'identity_db')
            for database in ['identity_db', 'booking_db']:
                for suffix, status, expiry in [('old', 'SUCCEEDED', "'2000-01-01'"),
                                              ('live', 'SUCCEEDED', 'UTC_TIMESTAMP(3)+INTERVAL 1 DAY'),
                                              ('pending', 'IN_PROGRESS', "'2000-01-01'")]:
                    s.sql("INSERT INTO idempotency_record(actor_id,operation,request_key,payload_hash,status,http_status,response_body,expires_at) VALUES (" + uid + ", 'RELIABILITY_PROBE','" + prefix + '-' + suffix + "','" + '0'*64 + "','" + status + "',200,'{}'," + expiry + ");", database)
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                old = sum(int(s.sql("SELECT COUNT(*) FROM idempotency_record WHERE request_key='" + prefix + "-old';", db)) for db in ['identity_db', 'booking_db'])
                refresh = int(s.sql("SELECT COUNT(*) FROM used_refresh_token WHERE token_hash='" + old_hash + "';", 'identity_db'))
                if old == 0 and refresh == 0: break
                time.sleep(.3)
            assert old == 0 and refresh == 0
            for database in ['identity_db', 'booking_db']:
                assert s.sql("SELECT COUNT(*) FROM idempotency_record WHERE request_key IN ('" + prefix + "-live','" + prefix + "-pending');", database) == '2'
            assert s.sql("SELECT COUNT(*) FROM used_refresh_token WHERE token_hash='" + live_hash + "';", 'identity_db') == '1'
            record('两个库定时清理过期成功记录，保留有效幂等记录、进行中记录及有效刷新历史')

            # 故意伪造用户头；并发请求仍共享已验签的同一用户额度。
            def query(index):
                return call('/resources', token=student, extra={'X-User-Id': str(index + 10000)})[0]
            with ThreadPoolExecutor(max_workers=12) as pool:
                statuses = list(pool.map(query, range(12)))
            assert statuses.count(200) == 8 and statuses.count(429) == 4, statuses
            record('Redis Lua 并发总额度精确生效，伪造用户头无法绕过')
            time.sleep(3.1)
            assert call('/resources', token=student)[0] == 200
            record('限流窗口结束后额度恢复')

            for _ in range(2): assert call('/availability/search', 'POST', {}, student)[0] == 400
            limited = call('/availability/search', 'POST', {}, student)
            assert limited[0] == 429 and limited[1]['code'] == 'BUSINESS_RATE_LIMITED'
            assert 1 <= int(limited[2]['Retry-After']) <= 3
            assert call('/resources', token=student)[0] == 200
            record('搜索独立额度、统一 429 与 Retry-After 生效，普通读取仍可用')

            time.sleep(3.1)
            for _ in range(2): assert call('/users/imports/preview', 'POST', {}, admin)[0] == 400
            assert call('/users/imports/preview', 'POST', {}, admin)[0] == 429
            record('人员导入独立额度生效')

            too_large = call('/auth/login', 'POST', {'username': 'x'*1500, 'password': 'x', 'deviceId': prefix, 'deviceType': 'WEB'})
            assert too_large[0] == 413 and too_large[1]['code'] == 'PAYLOAD_TOO_LARGE'
            record('Content-Length 超限提前返回统一 413')
            connection = http.client.HTTPConnection('127.0.0.1', 8081, timeout=10)
            try:
                body = json.dumps({'username': 'x'*1500, 'password': 'x', 'deviceId': prefix, 'deviceType': 'WEB'}).encode()
                connection.request('POST', '/api/v1/auth/login', body=iter([body[:800], body[800:]]),
                                   headers={'Content-Type': 'application/json'}, encode_chunked=True)
                response = connection.getresponse()
                result = json.loads(response.read())
                assert response.status == 413 and result['code'] == 'PAYLOAD_TOO_LARGE', (response.status, result.get('code'))
            finally: connection.close()
            record('无 Content-Length 的分块超限请求同样返回 413')

            # 只启动一个 Redis 地址错误的临时网关，Docker Redis 保持正常运行。
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
            stack.start('gateway', port, {'REDIS_PORT': '1'})
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                try:
                    if s.auth.request(f'http://127.0.0.1:{port}/actuator/health/liveness')[0] == 200: break
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError): pass
                time.sleep(.3)
            denied = s.auth.request(f'http://127.0.0.1:{port}/api/v1/resources', token=student)
            assert denied[0] == 503 and denied[1]['code'] == 'SERVICE_UNAVAILABLE'
            record('Redis 不可用时业务限流拒绝请求，未改写为 429，也未降级放行')

            time.sleep(3.1)
            with ThreadPoolExecutor(max_workers=8) as pool:
                assert list(pool.map(lambda _: call('/resources', token=student)[0], range(8))) == [200]*8
            assert call('/resources', token=student)[0] == 429
            assert call('/auth/logout', 'POST', {}, student)[0] == 200
            time.sleep(3.1)
            assert call('/resources', token=student)[0] == 401
            record('额度耗尽仍允许退出撤销，旧令牌无法通过实时校验')
            destination = s.auth.REPORT_DIR
            destination.mkdir(parents=True, exist_ok=True)
            (destination / 'backend-reliability.json').write_text(json.dumps({
                'date': s.auth.REPORT_DATE, 'mode': 'dev-business-real-infrastructure',
                'checks': checks, 'passed': len(checks), 'failed': 0,
                'scope': '过期记录、幂等有效期、并发业务限流、请求体上限和会话撤销；不代表生产容量压测'
            }, ensure_ascii=False, indent=2) + '\n')
    finally:
        # 前缀仅由本脚本内部 UUID 生成；删除本轮技术记录、审批组及临时账号。
        s.sql("DELETE FROM idempotency_record WHERE request_key LIKE '" + prefix + "%';"
              "DELETE FROM audit_log WHERE trace_id LIKE '" + prefix + "%';"
              "DELETE FROM approval_group WHERE name LIKE '" + prefix + "%';")
        s.cleanup_identity(prefix)
        print('PASS: 本轮临时进程和数据已清理', flush=True)

if __name__ == '__main__': main()
