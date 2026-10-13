/**
 * 当前页面的身份与令牌状态。
 * 访问和刷新令牌仅保存在内存，刷新页面后需重新登录；localStorage 只保存设备标识。
 * 多个请求同时遇到 401 时共用一次刷新，避免一次性刷新令牌被并发重复使用。
 */
import {defineStore} from 'pinia'
import {ref, computed} from 'vue'
import type {Actor, Tokens} from '../types'

export const useSession = defineStore('session', () => {
    const accessToken = ref(''), refreshToken = ref(''), actor = ref<Actor | null>(null)
    // 保存正在进行的刷新 Promise；其他并发请求直接等待同一结果。
    let flight: Promise<void> | null = null
    const deviceId = localStorage.getItem('xmut-device') || crypto.randomUUID()
    localStorage.setItem('xmut-device', deviceId)
    const signedIn = computed(() => !!accessToken.value && !!actor.value)
    const manager = computed(() => actor.value?.roles.some(r => r === 'RESOURCE_ADMIN' || r === 'SYSTEM_ADMIN') ?? false)
    const admin = computed(() => actor.value?.roles.includes('SYSTEM_ADMIN') ?? false)

    // 清理页面身份及令牌；保留设备编号，使下一次登录仍能被服务端识别为同一设备。
    function clear() {
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
        const r = await fetch('/api/v1' + path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'X-Trace-Id': crypto.randomUUID()},
            body: JSON.stringify(payload),
            signal: AbortSignal.timeout(15000)
        })
        const b = await r.json();
        if (!r.ok) throw new Error(b.message || '登录服务暂时不可用');
        return b.data
    }

    // 先清除旧身份，再登录并查询 /users/me；获取身份失败时不能保留半登录状态。
    async function login(username: string, password: string) {
        clear();
        accept(await publicCall('/auth/login', {username, password, deviceId, deviceType: 'WEB'}));
        try {
            const r = await fetch('/api/v1/users/me', {
                headers: {Authorization: 'Bearer ' + accessToken.value},
                signal: AbortSignal.timeout(15000)
            });
            const b = await r.json();
            if (!r.ok) throw new Error(b.message || '无法获取身份');
            actor.value = b.data
        } catch (e) {
            clear();
            throw e
        }
    }

    // sent 是失败请求实际发送的旧令牌。若页面令牌已更新，说明别的请求已完成刷新，直接复用即可。
    async function refresh(sent: string) {
        if (accessToken.value && sent !== accessToken.value) return
        if (flight) return flight
        if (!refreshToken.value) {
            clear();
            throw new Error('请重新登录')
        }
        flight = (async () => {
            try {
                accept(await publicCall('/auth/refresh', {refreshToken: refreshToken.value, deviceId}))
            } catch (e) {
                clear();
                throw e
            } finally {
                flight = null
            }
        })();
        return flight
    }

    // 尽力请求服务端撤销当前会话；即使网络出错也清除本地身份，避免界面继续表现为已登录。
    async function logout() {
        try {
            if (accessToken.value) await fetch('/api/v1/auth/logout', {
                method: 'POST',
                headers: {Authorization: 'Bearer ' + accessToken.value, 'Content-Type': 'application/json'},
                body: '{}',
                signal: AbortSignal.timeout(10000)
            })
        } finally {
            clear()
        }
    }

    return {accessToken, actor, signedIn, manager, admin, login, refresh, logout, clear}
})
