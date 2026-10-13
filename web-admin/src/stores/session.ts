/**
 * 当前页面的身份与令牌状态。
 * 访问和刷新令牌仅保存在内存，刷新页面后需重新登录；localStorage 只保存设备标识。
 * 多个请求同时遇到 401 时共用一次刷新，避免一次性刷新令牌被并发重复使用。
 */
import {defineStore} from 'pinia'
import {ref, computed} from 'vue'
import type {Actor, Tokens} from '../types'
import {ApiError, fetchResponse, readResponse} from '../api/http'

export const useSession = defineStore('session', () => {
    const accessToken = ref(''), refreshToken = ref(''), actor = ref<Actor | null>(null), sessionVersion = ref(0)
    // 保存正在进行的刷新 Promise；其他并发请求直接等待同一结果。
    let flight: Promise<void> | null = null
    const deviceId = localStorage.getItem('xmut-device') || crypto.randomUUID()
    localStorage.setItem('xmut-device', deviceId)
    const signedIn = computed(() => !!accessToken.value && !!actor.value)
    const manager = computed(() => actor.value?.roles.some(r => r === 'RESOURCE_ADMIN' || r === 'SYSTEM_ADMIN') ?? false)
    const admin = computed(() => actor.value?.roles.includes('SYSTEM_ADMIN') ?? false)

    // 清理页面身份及令牌；保留设备编号，使下一次登录仍能被服务端识别为同一设备。
    function clear() {
        // 身份代数只在退出/重登录时变化，正常令牌轮换保持不变。
        sessionVersion.value++;
        flight = null;
        accessToken.value = '';
        refreshToken.value = '';
        actor.value = null
    }

    // 每次刷新都会轮换 refreshToken，访问与刷新凭据须一起更新。
    function accept(data: Tokens) {
        accessToken.value = data.accessToken;
        refreshToken.value = data.refreshToken
    }

    // 登录和刷新不依赖已有访问令牌，因此独立发送请求，避免使用业务 api 导致递归刷新。
    async function publicCall(path: string, payload: unknown): Promise<Tokens> {
        const r = await fetchResponse(path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'X-Trace-Id': crypto.randomUUID()},
            body: JSON.stringify(payload)
        })
        const tokens = await readResponse<Tokens>(r)
        if (!tokens || typeof tokens.accessToken !== 'string' || !tokens.accessToken
            || typeof tokens.refreshToken !== 'string' || !tokens.refreshToken)
            throw new ApiError('登录服务响应格式不正确，请稍后重试', r.status, 'INVALID_RESPONSE')
        return tokens
    }

    // 令牌与身份必须来自同一次登录；退出或新的登录开始后，丢弃旧请求的迟到结果。
    async function login(username: string, password: string) {
        clear()
        const version = sessionVersion.value
        const current = () => {
            if (version !== sessionVersion.value) throw new ApiError('登录身份已改变，请重新操作', 401, 'SESSION_CHANGED')
        }
        try {
            const tokens = await publicCall('/auth/login', {username, password, deviceId, deviceType: 'WEB'})
            current()
            accept(tokens)
            const r = await fetchResponse('/users/me', {
                headers: {Authorization: 'Bearer ' + accessToken.value, 'X-Trace-Id': crypto.randomUUID()}
            })
            const identity = await readResponse<Actor>(r)
            current()
            actor.value = identity
        } catch (e) {
            if (version === sessionVersion.value) clear()
            throw e
        }
    }

    // sent 是失败请求实际发送的旧令牌。若页面令牌已更新，说明别的请求已完成刷新，直接复用即可。
    async function refresh(sent: string) {
        if (accessToken.value && sent !== accessToken.value) return
        if (flight) return flight
        if (!refreshToken.value) {
            clear()
            throw new ApiError('登录状态已失效，请重新登录', 401, 'SESSION_EXPIRED')
        }
        const version = sessionVersion.value
        const task: Promise<void> = (async () => {
            try {
                const tokens = await publicCall('/auth/refresh', {refreshToken: refreshToken.value, deviceId})
                if (version !== sessionVersion.value)
                    throw new ApiError('登录身份已改变，请重新操作', 401, 'SESSION_CHANGED')
                accept(tokens)
            } catch (e) {
                if (version === sessionVersion.value) clear()
                throw e
            } finally {
                // 旧刷新结束时不能清掉另一身份正在进行的刷新任务。
                if (version === sessionVersion.value) flight = null
            }
        })()
        flight = task
        return task
    }

    // 先清理本地身份，再尽力撤销服务器会话，迟到刷新不能重新写回令牌。
    async function logout() {
        const token = accessToken.value
        clear()
        try {
            if (token) await fetchResponse('/auth/logout', {
                method: 'POST',
                headers: {Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'X-Trace-Id': crypto.randomUUID()},
                body: '{}'
            })
        } catch {
            // 已离线也保持退出状态；不把网络异常变成未处理的页面错误。
        }
    }

    return {accessToken, sessionVersion, actor, signedIn, manager, admin, login, refresh, logout, clear}
})
