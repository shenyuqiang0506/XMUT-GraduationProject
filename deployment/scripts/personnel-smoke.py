#!/usr/bin/env python3
"""Verify student/teacher account imports against the isolated local dev stack."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location('business_support', Path(__file__).with_name('business-test-support.py'))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
BASE = 'http://127.0.0.1:8081/api/v1'


class PersonnelStack(s.Stack):
    # 独立 audience 隔离测试登录限流键，不修改本机日常使用的账号凭据或 Redis 服务。
    def start(self, name, port, overrides=None):
        super().start(name, port, dict(overrides or {}, JWT_AUDIENCE='xmut-dev-api-' + self.group))


def main():
    # 编号故意以 00 开头，直接验证前导零经过接口和数据库之后仍保留。
    prefix = '00p' + uuid.uuid4().hex[:10]
    password = uuid.uuid4().hex + 'Aa1!'
    private = s.runtime.load_env(s.PRIVATE / 'identity.env')
    checks = []

    def record(name):
        checks.append({'check': name, 'result': 'PASS'})
        print('PASS:', name, flush=True)

    def call(path, method='GET', payload=None, token=None, key=None):
        return s.auth.request(BASE + path, method, payload, token, prefix + '-request',
                              key or (prefix + '-' + uuid.uuid4().hex[:16]) if method != 'GET' else None)

    def row(suffix, role='TEACHER'):
        return {'number': prefix + suffix, 'displayName': '测试人员', 'roleCode': role, 'initialPassword': password}

    def login(username, suffix, pwd=password):
        status, body, _ = call('/auth/login', 'POST', {'username': username, 'password': pwd,
                              'deviceId': prefix + suffix, 'deviceType': 'WEB'})
        assert status == 200, f'Login HTTP {status}, code={body.get("code")}'
        return body['data']['accessToken']

    def count(suffix):
        return s.sql("SELECT COUNT(*) FROM `user` WHERE username='" + prefix + suffix + "';", 'identity_db')

    try:
        with PersonnelStack('personnel'):
            admin = login(private['AUTH_ADMIN_USERNAME'], '-admin', private['AUTH_ADMIN_PASSWORD'])
            payload = {'rows': [row('001', 'STUDENT'), row('002')]}
            # 预览不保存账号，也不返回密码或密码摘要。
            status, body, _ = call('/users/imports/preview', 'POST', payload, admin)
            assert status == 200 and body['data']['canImport'] and body['data']['total'] == 2
            assert count('001') == count('002') == '0'
            assert password not in json.dumps(body)
            record('学号/工号预览通过且无写入，不泄露密码')

            key = prefix + '-import'
            status, body, _ = call('/users/imports', 'POST', payload, admin, key)
            assert status == 201 and body['data']['created'] == 2
            created = body['data']
            student_id, teacher_id = [x['id'] for x in created['items']]
            assert [x['username'] for x in created['items']] == [prefix + '001', prefix + '002']
            assert password not in json.dumps(body) and 'passwordHash' not in json.dumps(body)
            status, replay, headers = call('/users/imports', 'POST', payload, admin, key)
            assert status == 201 and replay['data'] == created and headers['Idempotency-Replayed'] == 'true'
            changed = {'rows': [dict(payload['rows'][0], initialPassword=password + 'x'), payload['rows'][1]]}
            assert call('/users/imports', 'POST', changed, admin, key)[0] == 409
            record('整批创建保留编号前导零，重试回放，修改密码复用幂等键被拒绝')

            student = login(prefix + '001', '-student')
            teacher = login(prefix + '002', '-teacher')
            for token, role in [(student, 'STUDENT'), (teacher, 'TEACHER')]:
                status, body, _ = call('/users/me', token=token)
                assert status == 200 and body['data']['roles'] == [role]
                assert call('/resources', token=token)[0] == 200
                assert call('/users', token=token)[0] == 403
                assert call('/users/imports/preview', 'POST', payload, token)[0] == 403
                assert call('/users/imports', 'POST', payload, token)[0] == 403
                assert call('/admin/approval-groups', token=token)[0] == 403
            record('学生和教师可登录与查询，不能开户、批量导入或管理审批组')

            for rows, code in [([row('003'), dict(row('003'), roleCode='STUDENT')], 'DUPLICATE_NUMBER'),
                               ([row('004', 'SYSTEM_ADMIN')], 'INVALID_ROLE'),
                               ([dict(row('005'), initialPassword='密' * 25)], 'INVALID_PASSWORD')]:
                status, body, _ = call('/users/imports/preview', 'POST', {'rows': rows}, admin)
                assert status == 200 and not body['data']['canImport']
                assert any(x['code'] == code for x in body['data']['errors'])
                assert call('/users/imports', 'POST', {'rows': rows}, admin)[0] == 422
            assert count('003') == count('004') == count('005') == '0'
            assert call('/users/imports/preview', 'POST', {'rows': []}, admin)[0] == 400
            assert call('/users/imports', 'POST', {'rows': [row('006')] * 51}, admin)[0] == 400
            record('批内重复、管理角色、超长多字节密码和批次规模均被拒绝')

            conflict = {'rows': [row('007'), row('002')]}
            status, body, _ = call('/users/imports/preview', 'POST', conflict, admin)
            assert status == 200 and body['data']['errors'][0]['code'] == 'USERNAME_EXISTS'
            assert call('/users/imports', 'POST', conflict, admin)[0] == 409 and count('007') == '0'
            # 旧账号仍能用原密码登录，批量导入不能成为隐式的重置密码入口。
            assert login(prefix + '002', '-unchanged')
            record('已存在工号使整批失败，不留半批账号且原密码保持有效')

            racing = {'rows': [row('008'), row('009', 'STUDENT')]}
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda n: call('/users/imports', 'POST', racing, admin, prefix + '-race' + str(n)), [1, 2]))
            assert sorted(x[0] for x in results) == [201, 409]
            assert count('008') == count('009') == '1'
            record('两个不同幂等键并发导入同一名单，只有一批成功，无重复账号')

            # 验证单条创建入口同样支持教师，管理权仍需系统管理员显式授予。
            single = dict(username=prefix+'010', displayName='教师单条开户', initialPassword=password, roleCodes=['TEACHER'])
            status, body, _ = call('/users', 'POST', single, admin)
            assert status == 201 and body['data']['roles'] == ['TEACHER']
            status, _, _ = call('/users/' + teacher_id + '/roles', 'PUT',
                               {'roleCodes': ['TEACHER', 'RESOURCE_ADMIN'], 'expectedVersion': 0}, admin)
            assert status == 200 and call('/users/me', token=teacher)[0] == 401
            teacher_admin = login(prefix + '002', '-authorized')
            assert set(call('/users/me', token=teacher_admin)[1]['data']['roles']) == {'TEACHER', 'RESOURCE_ADMIN'}
            assert call('/users/imports', 'POST', payload, teacher_admin)[0] == 403
            assert call('/admin/approval-groups', token=teacher_admin)[1]['data']['items'] == []
            status, _, _ = call('/users/' + teacher_id + '/roles', 'PUT',
                               {'roleCodes': ['TEACHER'], 'expectedVersion': 1}, admin)
            assert status == 200 and call('/users/me', token=teacher_admin)[0] == 401
            record('教师单条开户与显式授权有效，角色修改撤销旧会话，未分组时无管理范围')

            saved = s.sql("SELECT CAST(response_body AS CHAR),payload_hash FROM idempotency_record WHERE request_key='" + key + "';", 'identity_db')
            assert password not in saved and '$2' not in saved
            hashes = s.sql("SELECT password_hash FROM `user` WHERE username LIKE '" + prefix + "%';", 'identity_db')
            assert hashes and all(x.startswith('$2') for x in hashes.splitlines()) and password not in hashes
            record('数据库仅保存 BCrypt 密码，幂等响应不含密码或密码摘要')

            if os.getenv('XMUT_WEB_TEST') == '1':
                env = os.environ.copy()
                env.update(TEST_PERSONNEL_TEACHER=prefix+'002', TEST_PERSONNEL_PASSWORD=password,
                           TEST_ADMIN_USERNAME=private['AUTH_ADMIN_USERNAME'], TEST_ADMIN_PASSWORD=private['AUTH_ADMIN_PASSWORD'])
                subprocess.run(['npm', 'test', '--', 'tests/personnel.spec.ts'], cwd=s.REPO/'web-admin', env=env, check=True)
                report = json.loads((s.REPO/'web-admin/test-results/results.json').read_text())
                assert report['stats']['expected'] == 2 and report['stats']['unexpected'] == 0
                record('浏览器通过工号登录、教师路由隔离与开户表单学号/工号切换')
            s.auth.REPORT_DIR.mkdir(parents=True, exist_ok=True)
            (s.auth.REPORT_DIR/'personnel-accounts.json').write_text(json.dumps(
                {'date': s.auth.REPORT_DATE, 'checks': checks, 'passed': len(checks), 'failed': 0,
                 'scope': '学生教师角色与 JSON 批量开户；临时数据已在 finally 清理，不涉及 prod'}, ensure_ascii=False, indent=2)+'\n')
    finally:
        # 只清理本轮前缀所属的账号、会话和审计；保留 V2 教师角色迁移与已有管理员。
        s.cleanup_identity(prefix)


if __name__ == '__main__':
    main()
