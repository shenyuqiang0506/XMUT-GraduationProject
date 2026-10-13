#!/usr/bin/env python3
"""Initialize Nacos admin once and seed a non-secret environment marker."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

# 读取目标环境的 Nacos 管理员凭据，完成首次初始化和环境标记校验。
# 重复执行优先尝试登录；不会自动覆盖已有的业务配置。
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=['dev', 'prod'])
    env = parser.parse_args().environment
    values = dict(line.split('=', 1) for line in (ROOT / f'.env.{env}').read_text().splitlines()
                  if line and not line.startswith('#'))
    if values['APP_ENV'] != env:
        raise SystemExit('Environment mismatch')
    base = 'http://127.0.0.1:' + ('8848' if env == 'dev' else '18848') + '/nacos'
    # 统一封装 Nacos 表单请求与 accessToken 请求头，超时后交由调用方处理。
    def request(method, path, fields=None, token=None):
        fields = fields or {}
        encoded = urllib.parse.urlencode(fields)
        url = base + path + (('?' + encoded) if method == 'GET' and encoded else '')
        data = encoded.encode() if method == 'POST' else None
        headers = {'Content-Type': 'application/x-www-form-urlencoded'}
        if token:
            headers['accessToken'] = token
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=15) as response:
            result = json.load(response)
        if isinstance(result, dict) and result.get('code', 0) != 0:
            # Do not include the response; bootstrap responses may contain credentials.
            raise RuntimeError(f'Nacos rejected {path}, code={result.get("code")}')
        return result.get('data', result) if isinstance(result, dict) else result
    # 先登录；尚未初始化时调用管理员初始化接口，再验证同一密码能否登录。
    login_fields = {'username': values['NACOS_USERNAME'], 'password': values['NACOS_PASSWORD']}
    try:
        login = request('POST', '/v3/auth/user/login', login_fields)
    except (urllib.error.HTTPError, RuntimeError):
        request('POST', '/v3/auth/user/admin', {'password': values['NACOS_PASSWORD']})
        login = request('POST', '/v3/auth/user/login', login_fields)
    token = login['accessToken']
    # 仅发布不含密钥的环境标记；已存在配置必须保留正确标记，写入后再回读核对。
    for data_id in ['GraduationProject.properties', 'booking-service.properties', 'identity-service.properties', 'ai-service.properties', 'gateway.properties','notification-service.properties']:
        fields = {'dataId': data_id, 'groupName': 'DEFAULT_GROUP', 'namespaceId': 'public'}
        expected = f'platform.environment={env}\n'
        try:
            current = request('GET', '/v3/admin/cs/config', fields, token)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            current = None
        except RuntimeError as error:
            if 'code=30004' not in str(error):
                raise
            current = None
        if current is None:
            request('POST', '/v3/admin/cs/config', dict(fields, content=expected, type='properties'), token)
        elif f'platform.environment={env}' not in current.get('content', '').splitlines():
            raise SystemExit('Existing configuration has a different/missing environment marker; not overwritten.')
        actual = request('GET', '/v3/admin/cs/config', fields, token)
        if f'platform.environment={env}' not in actual['content'].splitlines():
            raise SystemExit('Nacos config verification failed')
    print(f'{env}: Nacos admin login and environment config verified; credentials not printed.')

if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit(f'Nacos HTTP {error.code}; response omitted to protect credentials.')
    except urllib.error.URLError:
        raise SystemExit('Nacos connection failed; confirm the selected environment is running and healthy.')
