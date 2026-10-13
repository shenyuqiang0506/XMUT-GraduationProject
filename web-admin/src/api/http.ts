/** 请求传输与响应解析，不依赖会话仓库，避免登录、刷新与业务客户端循环调用。 */
export class ApiError extends Error {
    constructor(message: string, public status: number, public code: string, public traceId = '') {
        super(message)
        this.name = 'ApiError'
    }
}

export async function fetchResponse(path: string, init: RequestInit): Promise<Response> {
    try {
        return await fetch('/api/v1' + path, {...init, signal: init.signal ?? AbortSignal.timeout(15000)})
    } catch (error) {
        if (error instanceof Error && ['TimeoutError', 'AbortError'].includes(error.name))
            throw new ApiError('请求超时，请稍后重试；写入结果可能尚未确认', 0, 'REQUEST_TIMEOUT')
        throw new ApiError('网络连接失败，请检查连接后重试', 0, 'NETWORK_ERROR')
    }
}

/**
 * 网关可能返回 HTML 或空响应，不能直接把 JSON 解析器的英文错误展示给用户。
 * 错误保留真实 HTTP 状态和追踪号；服务故障不能统一改成登录过期。
 */
export async function readResponse<T>(response: Response): Promise<T> {
    const trace = response.headers.get('X-Trace-Id') || ''
    let body: unknown
    try { body = await response.json() }
    catch { throw new ApiError('服务返回了非预期的响应，请稍后重试', response.status, 'INVALID_RESPONSE', trace) }
    if (!body || typeof body !== 'object' || Array.isArray(body))
        throw new ApiError('服务响应格式不正确，请稍后重试', response.status, 'INVALID_RESPONSE', trace)
    const data = body as Record<string, unknown>
    const traceId = typeof data.traceId === 'string' ? data.traceId : trace
    if (!response.ok) {
        const message = typeof data.message === 'string' && data.message ? data.message
            : response.status === 401 ? '登录状态已失效，请重新登录' : '请求失败，请稍后重试'
        throw new ApiError(message, response.status, typeof data.code === 'string' ? data.code : 'ERROR', traceId)
    }
    if (data.code !== 'OK' || !('data' in data))
        throw new ApiError('服务响应格式不正确，请稍后重试', response.status, 'INVALID_RESPONSE', traceId)
    return data.data as T
}
