import {test, expect} from '@playwright/test'
import {jsonObject, stringObject} from '../src/utils/forms'
import {shanghaiInstant, shanghaiRange} from '../src/utils/time'
import {fetchResponse, readResponse} from '../src/api/http'
import {mutationKeys} from '../src/api/client'
import {usePagedOptions} from '../src/composables/usePagedOptions'
import type {Page} from '../src/types'

test('JSON 输入错误使用中文，并拒绝数组、null 及非字符串映射', () => {
    expect(() => jsonObject('{bad', '字段映射')).toThrow('字段映射 JSON 格式不正确')
    for (const value of ['null', '[]', '1', '"text"'])
        expect(() => jsonObject(value, '来源说明')).toThrow('必须是 JSON 对象')
    expect(() => stringObject('{"PROJECTOR":true}', '设施配置')).toThrow('每个值都必须是字符串')
    expect(stringObject('{"sourceId":"编号"}', '字段映射')).toEqual({sourceId: '编号'})
})

test('上海时刻校验日期、区间顺序和自然日，不依赖本地时区', () => {
    expect(shanghaiInstant('2026-10-15 08:30:00', '时间')).toBe('2026-10-15T08:30:00+08:00')
    expect(() => shanghaiInstant('2026-02-30 08:00:00', '时间')).toThrow('不是有效日期')
    expect(() => shanghaiRange('2026-10-15 10:00:00', '2026-10-15 09:00:00')).toThrow('结束时间必须晚于')
    expect(() => shanghaiRange('2026-10-15 23:00:00', '2026-10-16 00:00:00', true)).toThrow('同一上海自然日')
    expect(shanghaiRange('2026-10-15 23:00:00', '2026-10-16 00:00:00').endAt).toContain('+08:00')
})

test('分页合并保护并发、失败不跳页、跨页重复按 ID 更新', async () => {
    const calls: number[] = []
    let release!: (page: Page<{id: string; name: string}>) => void
    let failed = false
    const options = usePagedOptions<{id: string; name: string}>(async page => {
        calls.push(page)
        if (page === 1) return new Promise(resolve => { release = resolve })
        if (!failed) { failed = true; throw new Error('临时网络故障') }
        return {page: 2, size: 2, total: 3, items: [{id: '2', name: '更新'}, {id: '3', name: '三'}]}
    })
    const first = options.more()
    await options.more()
    expect(calls).toEqual([1])
    release({page: 1, size: 2, total: 3, items: [{id: '1', name: '一'}, {id: '2', name: '二'}]})
    await first
    await expect(options.more()).rejects.toThrow('临时网络故障')
    expect(options.loading.value).toBe(false)
    await options.more()
    expect(calls).toEqual([1, 2, 2])
    expect(options.items.value.map(row => row.id)).toEqual(['1', '2', '3'])
    expect(options.items.value[1]?.name).toBe('更新')
    await options.more()
    expect(calls).toEqual([1, 2, 2])
})

test('HTML、空响应和错误信封保留 HTTP 状态，返回统一请求错误', async () => {
    await expect(readResponse(new Response('<html>Bad Gateway</html>', {status: 502, headers: {'X-Trace-Id': 'gateway-error'}})))
        .rejects.toMatchObject({status: 502, code: 'INVALID_RESPONSE', traceId: 'gateway-error'})
    await expect(readResponse(new Response('', {status: 200}))).rejects.toMatchObject({code: 'INVALID_RESPONSE'})
    await expect(readResponse(new Response('null'))).rejects.toMatchObject({code: 'INVALID_RESPONSE'})
    await expect(readResponse(new Response(JSON.stringify({code: 'SERVICE_UNAVAILABLE', message: '身份服务暂不可用'}), {status: 503})))
        .rejects.toMatchObject({status: 503, code: 'SERVICE_UNAVAILABLE', message: '身份服务暂不可用'})
})

test('超时和断网使用中文且保持不同错误码', async () => {
    const original = globalThis.fetch
    try {
        globalThis.fetch = async () => { throw new DOMException('timeout', 'TimeoutError') }
        await expect(fetchResponse('/resources', {})).rejects.toMatchObject({status: 0, code: 'REQUEST_TIMEOUT'})
        globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
        await expect(fetchResponse('/resources', {})).rejects.toMatchObject({status: 0, code: 'NETWORK_ERROR'})
    } finally { globalThis.fetch = original }
})

test('写入结果未知时保留幂等键，明确成功后下一次操作生成新键', () => {
    const keys = mutationKeys(), payload = {name: '测试资源'}
    const original = keys.key('create', payload)
    // 模拟响应丢失：不能调用 done，用户重试仍获得同一键。
    expect(keys.key('create', {...payload})).toBe(original)
    expect(keys.key('other', payload)).not.toBe(original)
    keys.done('create', payload)
    expect(keys.key('create', payload)).not.toBe(original)
    // 操作名与内容边界不得因简单字符串拼接发生碰撞。
    expect(keys.key('a', 12)).not.toBe(keys.key('a1', 2))
})
