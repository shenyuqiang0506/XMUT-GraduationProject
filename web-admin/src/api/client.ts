/** 正式业务 API 入口：访问令牌、追踪编号、幂等键及一次刷新重试。 */
import {useSession} from '../stores/session'
import {ApiError, fetchResponse, readResponse} from './http'
export {ApiError} from './http'

export async function api<T>(path: string, method = 'GET', body?: unknown, key?: string): Promise<T> {
    const session = useSession(), sent = session.accessToken, version = session.sessionVersion
    if (session.refreshUncertain)
        throw new ApiError('刷新结果尚未确认，请保留编辑内容后重新登录', 401, 'REFRESH_UNCERTAIN')
    const headers: Record<string, string> = {'X-Trace-Id': crypto.randomUUID(), Authorization: 'Bearer ' + sent}
    if (key) headers['Idempotency-Key'] = key
    if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
    const run = () => fetchResponse(path, {
        method, headers,
        body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body)
    })
    // 退出或切换账号后，旧请求不能用新账号的令牌重试，也不能清空新会话。
    const current = () => {
        if (version !== session.sessionVersion)
            throw new ApiError('登录身份已改变，请重新操作', 401, 'SESSION_CHANGED')
    }
    let response = await run()
    current()
    if (response.status === 401) {
        await session.refresh(sent)
        current()
        headers.Authorization = 'Bearer ' + session.accessToken
        response = await run()
        current()
    }
    if (response.status === 401) session.clear()
    const result = await readResponse<T>(response)
    current()
    return result
}

/**
 * 页面内按操作与内容保留幂等键，只有明确成功才清理。
 * 超时、断网或无效响应时服务器可能已经写入，不能在 catch/finally 里删除原键。
 */
export function mutationKeys() {
    const keys = new Map<string, string>()
    const fingerprint = (scope: string, payload: unknown) => JSON.stringify([scope, payload])
    return {
        key: (scope: string, payload: unknown) => {
            const value = fingerprint(scope, payload)
            if (!keys.has(value)) keys.set(value, crypto.randomUUID())
            return keys.get(value)!
        },
        done: (scope: string, payload: unknown) => keys.delete(fingerprint(scope, payload))
    }
}

export function explain(error: unknown) {
    return error instanceof ApiError ? `${error.message}${error.traceId ? '（请求编号 ' + error.traceId + '）' : ''}`
        : error instanceof Error ? error.message : '请求失败，请稍后重试'
}
