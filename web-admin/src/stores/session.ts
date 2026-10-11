import {defineStore} from 'pinia'
import {ref, computed} from 'vue'
import type {Actor, Tokens} from '../types'

export const useSession = defineStore('session', () => {
    const accessToken = ref(''), refreshToken = ref(''), actor = ref<Actor | null>(null)
    let flight: Promise<void> | null = null
    const deviceId = localStorage.getItem('xmut-device') || crypto.randomUUID()
    localStorage.setItem('xmut-device', deviceId)
    const signedIn = computed(() => !!accessToken.value && !!actor.value)
    const manager = computed(() => actor.value?.roles.some(r => r !== 'STUDENT') ?? false)
    const admin = computed(() => actor.value?.roles.includes('SYSTEM_ADMIN') ?? false)

    function clear() {
        accessToken.value = '';
        refreshToken.value = '';
        actor.value = null
    }

    function accept(data: Tokens) {
        accessToken.value = data.accessToken;
        refreshToken.value = data.refreshToken
    }

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
