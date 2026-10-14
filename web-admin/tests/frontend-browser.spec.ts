import {test, expect, type Page, type Route} from '@playwright/test'

// 纯前端故障验收使用明确的模拟 API，不写入本机业务数据库。
// 非东八区浏览器用于检验日期控件的实际输入，而非仅检验工具函数。
test.use({timezoneId: 'America/Los_Angeles'})
const ok = (data: unknown) => ({code: 'OK', message: '成功', data, traceId: 'frontend-test'})
const tokens = {accessToken: 'test-access', refreshToken: 'test-refresh', expiresIn: 900}
const identity = {userId: '1', sessionId: '1', username: 'frontend-test', displayName: '前端测试', status: 'ACTIVE', authVersion: 0, roles: ['SYSTEM_ADMIN']}
const resource = (id: number) => ({id: String(id), name: '测试场地' + id, sourceSystem: 'TEST', sourceId: String(id), dataMode: 'REAL', resourceType: 'VENUE', platformStatus: 'DRAFT', sourceBorrowable: 'UNKNOWN', capacity: null, features: {}, version: 0})
async function json(route: Route, data: unknown, status = 200) {
    await route.fulfill({status, contentType: 'application/json', body: JSON.stringify(data)})
}
async function install(page: Page, handler: (route: Route) => Promise<boolean> = async () => false) {
    await page.route('**/api/v1/**', async route => {
        if (await handler(route)) return
        const path = new URL(route.request().url()).pathname
        if (path === '/api/v1/auth/login' || path === '/api/v1/auth/refresh') return json(route, ok(tokens))
        if (path === '/api/v1/users/me') return json(route, ok(identity))
        if (path === '/api/v1/resources') return json(route, ok({items: [], total: 0, page: 1, size: 100}))
        if (path === '/api/v1/admin/approval-groups') return json(route, ok({items: []}))
        if (path.startsWith('/api/v1/admin/policies/')) return json(route, ok({configured: false, version: 0}))
        if (path === '/api/v1/auth/logout') return json(route, ok(null))
        return json(route, {code: 'UNEXPECTED_TEST_REQUEST', message: '测试中未预期的请求'}, 500)
    })
}
async function login(page: Page) {
    await page.goto('/login')
    await page.locator('input[name=username]').fill('frontend-test')
    await page.locator('input[name=password]').fill('frontend-test-password')
    await page.getByRole('button', {name: '登录', exact: true}).click()
    await expect(page.getByRole('heading', {name: '资源目录', exact: true})).toBeVisible()
}
async function fillDate(page: Page, label: string, value: string) {
    await page.getByLabel(label, {exact: true}).fill(value.replace(' ', 'T').replace(/:00$/, ''))
    await page.getByLabel(label, {exact: true}).press('Tab')
}

test('海外浏览器提交上海时刻，跨上海自然日查询在发送前被拒绝', async ({page}) => {
    const requests: Record<string, unknown>[] = []
    await install(page, async route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/availability/search') return false
        requests.push(route.request().postDataJSON())
        await json(route, ok({items: [], total: 0})); return true
    })
    await login(page)
    await page.getByRole('menuitem', {name: '可用时段查询'}).click()
    await fillDate(page, '开始时间', '2026-10-15 08:00:00')
    await fillDate(page, '结束时间', '2026-10-15 10:00:00')
    await page.getByRole('button', {name: '查询可用组合'}).click()
    await expect.poll(() => requests.length).toBe(1)
    expect(requests[0]?.startAt).toBe('2026-10-15T08:00:00+08:00')
    expect(requests[0]?.endAt).toBe('2026-10-15T10:00:00+08:00')
    // 洛杉矶夏令时跳过 02:30，但同一上海时刻必须仍可正确输入。
    await fillDate(page, '开始时间', '2027-03-14 02:30:00')
    await fillDate(page, '结束时间', '2027-03-14 04:00:00')
    await page.getByRole('button', {name: '查询可用组合'}).click()
    await expect.poll(() => requests.length).toBe(2)
    expect(requests[1]?.startAt).toBe('2027-03-14T02:30:00+08:00')
    await fillDate(page, '结束时间', '2027-03-15 10:00:00')
    await page.getByRole('button', {name: '查询可用组合'}).click()
    await expect(page.getByRole('alert').filter({hasText: '同一上海自然日'})).toBeVisible()
    expect(requests).toHaveLength(2)
})

for (const [menu, button] of [['可用时段查询', '加载更多场地'], ['资料导入', '加载更多资源'], ['开放与占用管理', '加载更多资源']]) {
    test(menu + '加载失败后重试原页，快速点击只发一个请求', async ({page}) => {
        const requested: number[] = []
        let attempt = 0, release!: () => void
        await install(page, async route => {
            const url = new URL(route.request().url())
            if (url.pathname !== '/api/v1/resources') return false
            const pageNo = Number(url.searchParams.get('page') || 1)
            if (pageNo === 1) {
                await json(route, ok({page: 1, size: 100, total: 101, items: Array.from({length: 100}, (_, i) => resource(i + 1))}))
            } else {
                requested.push(pageNo)
                if (attempt++ === 0) await json(route, {code: 'SERVICE_UNAVAILABLE', message: '本次加载失败'}, 503)
                else {
                    await new Promise<void>(resolve => { release = resolve })
                    await json(route, ok({page: 2, size: 100, total: 101, items: [resource(100), resource(101)]}))
                }
            }
            return true
        })
        await login(page)
        await page.getByRole('menuitem', {name: menu, exact: true}).click()
        if (menu === '资料导入') {
            await page.getByLabel('导入内容', {exact: true}).press('ArrowDown')
            await page.getByRole('option', {name: '来源占用快照', exact: true}).click()
        }
        const more = page.getByRole('button', {name: button, exact: true})
        await more.click()
        await expect(page.getByRole('alert').filter({hasText: '本次加载失败'})).toBeVisible()
        await more.evaluate(element => { (element as HTMLButtonElement).click(); (element as HTMLButtonElement).click() })
        await expect.poll(() => requested.length).toBe(2)
        expect(requested).toEqual([2, 2])
        await expect(more).toBeDisabled()
        release()
        await expect(more).toHaveCount(0)
    })
}

test('设施和导入 JSON 错误显示中文，并阻止发送写请求', async ({page}) => {
    let writes = 0
    await install(page, async route => {
        if (route.request().method() === 'POST' && !new URL(route.request().url()).pathname.startsWith('/api/v1/auth/')) {
            writes++; await json(route, ok(null)); return true
        }
        return false
    })
    await login(page)
    await page.getByRole('button', {name: '新增资源', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: '新增资源'})
    await dialog.getByLabel('设施 JSON（如 PROJECTOR、WHITEBOARD）', {exact: true}).fill('{bad')
    await dialog.getByRole('button', {name: '保存资源'}).click()
    await expect(page.getByText('设施配置 JSON 格式不正确', {exact: false})).toBeVisible()
    await dialog.getByRole('button', {name: '关闭此对话框'}).click()
    await page.getByRole('menuitem', {name: '资料导入', exact: true}).click()
    await page.getByTestId('import-file').setInputFiles({name: 'test.json', mimeType: 'application/json', buffer: Buffer.from('[]')})
    await page.getByText('字段映射与来源说明（JSON）', {exact: true}).click()
    await page.getByLabel('字段映射：目标字段 → 原始列名', {exact: true}).fill('null')
    await page.getByRole('button', {name: '预览导入'}).click()
    await expect(page.getByRole('alert').filter({hasText: '字段映射必须是 JSON 对象'})).toBeVisible()
    expect(writes).toBe(0)
})

test('HTML 网关错误在页面显示中文与追踪号', async ({page}) => {
    await install(page, async route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/resources') return false
        await route.fulfill({status: 502, contentType: 'text/html', headers: {'X-Trace-Id': 'html-gateway-test'}, body: '<html>Bad Gateway</html>'})
        return true
    })
    await login(page)
    await expect(page.getByRole('alert').filter({hasText: '服务返回了非预期的响应'})).toContainText('html-gateway-test')
})

test('刷新服务故障保持 503 类型，旧请求不会把服务故障改成登录过期', async ({page}) => {
    let failing = false
    await install(page, async route => {
        const path = new URL(route.request().url()).pathname
        if (failing && path === '/api/v1/auth/refresh') { await json(route, {code: 'SERVICE_UNAVAILABLE', message: '身份服务暂不可用'}, 503); return true }
        if (failing && path === '/api/v1/resources') { await json(route, {code: 'UNAUTHORIZED', message: '访问令牌过期'}, 401); return true }
        return false
    })
    await login(page)
    failing = true
    const error = await page.evaluate(async () => {
        const path = '/src/api/client.ts', client = await import(path)
        try { await client.api('/resources') }
        catch (e) { const failure = e as {status: number; code: string}; return {status: failure.status, code: failure.code} }
    })
    expect(error).toEqual({status: 503, code: 'SERVICE_UNAVAILABLE'})
    await expect(page).toHaveURL(/\/resources$/)
    await expect(page.getByRole('alert').filter({hasText: '刷新结果尚未确认'})).toBeVisible()
    // 未知 503 不能再次使用一次性刷新令牌；保留界面但暂停新请求。
    const next = await page.evaluate(async () => {
        const path = '/src/api/client.ts', client = await import(path)
        try { await client.api('/resources') } catch (e) { return (e as {code: string}).code }
    })
    expect(next).toBe('REFRESH_UNCERTAIN')
})

test('退出后迟到的刷新响应不能恢复旧会话', async ({page}) => {
    let release!: () => void, refreshing = false
    await install(page, async route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/auth/refresh') return false
        refreshing = true
        await new Promise<void>(resolve => { release = resolve })
        await json(route, ok({...tokens, accessToken: 'late-access'})); return true
    })
    await login(page)
    const outcome = page.evaluate(async () => {
        const path = '/src/stores/session.ts', module = await import(path)
        const session = module.useSession()
        try { await session.refresh(session.accessToken); return 'accepted' }
        catch (e) { return (e as {code: string}).code }
    })
    await expect.poll(() => refreshing).toBe(true)
    await page.getByRole('button', {name: '退出登录', exact: true}).click()
    release()
    expect(await outcome).toBe('SESSION_CHANGED')
    await expect(page).toHaveURL(/\/login$/)
    const token = await page.evaluate(async () => {
        const path = '/src/stores/session.ts'; return (await import(path)).useSession().accessToken
    })
    expect(token).toBe('')
})

test('并发 401 共用一次刷新，并复用原幂等键及更新后的令牌', async ({page}) => {
    let refreshes = 0, expired = 0, ready!: () => void
    const bothExpired = new Promise<void>(resolve => { ready = resolve })
    const replayed: {key: string; authorization: string}[] = []
    await install(page, async route => {
        const path = new URL(route.request().url()).pathname
        if (path === '/api/v1/auth/refresh') {
            refreshes++
            await bothExpired
            await json(route, ok({...tokens, accessToken: 'test-renewed'})); return true
        }
        if (path === '/api/v1/test-a' || path === '/api/v1/test-b') {
            const headers = route.request().headers()
            if (headers.authorization === 'Bearer test-access') {
                if (++expired === 2) ready()
                await json(route, {code: 'UNAUTHORIZED', message: '已过期'}, 401)
            } else {
                replayed.push({key: headers['idempotency-key']!, authorization: headers.authorization!})
                await json(route, ok({saved: true}))
            }
            return true
        }
        return false
    })
    await login(page)
    const saved = await page.evaluate(async () => {
        const path = '/src/api/client.ts', client = await import(path)
        return Promise.all([client.api('/test-a', 'POST', {value: 1}, 'retry-key-a'), client.api('/test-b', 'POST', {value: 2}, 'retry-key-b')])
    })
    expect(saved).toEqual([{saved: true}, {saved: true}])
    expect(refreshes).toBe(1)
    expect(replayed.map(row => row.key).sort()).toEqual(['retry-key-a', 'retry-key-b'])
    expect(replayed.every(row => row.authorization === 'Bearer test-renewed')).toBe(true)
})

for (const returnToA of [false, true]) {
    test('规则切换 ' + (returnToA ? 'A→B→A' : 'A→B') + ' 丢弃迟到响应，保存只使用当前资源版本', async ({page}) => {
        let first = true, release!: () => void
        const saved: {id: string; payload: Record<string, unknown>}[] = []
        await install(page, async route => {
            const url = new URL(route.request().url()), path = url.pathname
            if (path === '/api/v1/resources') {
                await json(route, ok({items: [resource(1), resource(2)], total: 2})); return true
            }
            const detail = path.match(/^\/api\/v1\/resources\/([12])$/)
            if (detail) {
                const old = detail[1] === '1' && first
                if (old) { first = false; await new Promise<void>(resolve => { release = resolve }) }
                await json(route, ok({...resource(Number(detail[1])), name: (old ? '迟到旧资源' : '当前资源') + detail[1], version: old ? 1 : 7, openingRules: []})); return true
            }
            if (path.endsWith('/external-occupancies')) {
                await json(route, ok({items: [], total: 0})); return true
            }
            const write = path.match(/^\/api\/v1\/admin\/resources\/([12])\/opening-rules$/)
            if (write) { saved.push({id: write[1]!, payload: route.request().postDataJSON()}); await json(route, ok({})); return true }
            return false
        })
        await login(page)
        await page.getByRole('menuitem', {name: '开放与占用管理', exact: true}).click()
        const choose = async (id: number) => {
            await page.getByLabel('选择资源', {exact: true}).click()
            await page.getByRole('option', {name: '测试场地' + id + ' · REAL', exact: true}).click()
        }
        await choose(1)
        await expect.poll(() => !!release).toBe(true)
        await expect(page.getByRole('button', {name: '保存开放规则'})).toHaveCount(0)
        await choose(2)
        await expect(page.getByRole('heading', {name: '开放规则 · 当前资源2 · 版本 7'})).toBeVisible()
        if (returnToA) {
            await choose(1)
            await expect(page.getByRole('heading', {name: '开放规则 · 当前资源1 · 版本 7'})).toBeVisible()
        }
        const oldResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/resources/1')
        release()
        // 等待旧请求确实完成再检查，避免仅在它尚未返回时得到“通过”。
        await (await oldResponse).finished()
        await page.evaluate(() => new Promise(requestAnimationFrame))
        const selected = returnToA ? '1' : '2'
        await expect(page.getByRole('heading', {name: '开放规则 · 当前资源' + selected + ' · 版本 7'})).toBeVisible()
        await page.getByRole('button', {name: '保存开放规则', exact: true}).click()
        await expect.poll(() => saved.length).toBe(1)
        expect(saved[0]).toEqual({id: selected, payload: {expectedVersion: 7, rules: []}})
        await expect(page.getByText('迟到旧资源1', {exact: false})).toHaveCount(0)
    })
}

for (const users of [false, true]) {
    test((users ? '账号' : '目录') + ' A→B→A 查询只接收最后结果，迟到错误不取消当前加载', async ({page}) => {
        const waiting: (() => void)[] = [], received: string[] = []
        let oldDone = false
        await install(page, async route => {
            const url = new URL(route.request().url())
            if (url.pathname !== (users ? '/api/v1/users' : '/api/v1/resources')) return false
            const filter = url.searchParams.get(users ? 'username' : 'campus')
            if (!filter) { await json(route, ok({items: [], total: 0})); return true }
            const index = received.length; received.push(filter)
            await new Promise<void>(resolve => { waiting[index] = resolve })
            if (index === 0) { await json(route, {code: 'SERVICE_UNAVAILABLE', message: '过期查询故障'}, 503); oldDone = true }
            else {
                const row = users ? {id: String(index), username: filter + index, displayName: filter + index, roles: ['STUDENT'], status: 'ACTIVE', authVersion: 0}
                    : {...resource(index), name: filter + index}
                await json(route, ok({items: [row], total: 1}))
            }
            return true
        })
        await login(page)
        if (users) await page.getByRole('menuitem', {name: '账号与权限', exact: true}).click()
        for (const value of ['A', 'B', 'A']) {
            await page.getByPlaceholder(users ? '筛选账号' : '校区', {exact: true}).fill(value)
            await page.getByRole('button', {name: '查询', exact: true}).click()
            await expect.poll(() => waiting.length).toBe(received.length)
        }
        expect(received).toEqual(['A', 'B', 'A'])
        waiting[0]!()
        await expect.poll(() => oldDone).toBe(true)
        await expect(page.locator('.el-table .el-loading-mask:not(.el-loading-fade-leave-active)')).toBeVisible()
        await expect(page.getByRole('alert').filter({hasText: '过期查询故障'})).toHaveCount(0)
        waiting[2]!()
        await expect(page.getByRole('cell').filter({hasText: 'A2'}).first()).toBeVisible()
        const oldResponse = page.waitForResponse(response => new URL(response.url()).searchParams.get(users ? 'username' : 'campus') === 'B')
        waiting[1]!()
        await (await oldResponse).finished()
        await page.evaluate(() => new Promise(requestAnimationFrame))
        await expect(page.getByRole('cell').filter({hasText: 'B1'})).toHaveCount(0)
        await expect(page.getByRole('cell').filter({hasText: 'A2'}).first()).toBeVisible()
    })
}

test('明确未执行的刷新故障保留表单，恢复后使用原刷新凭据安全重试', async ({page}) => {
    let attempts = 0
    await install(page, async route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/auth/refresh') return false
        attempts++
        if (attempts === 1) await json(route, {code: 'REFRESH_NOT_EXECUTED', message: '刷新尚未执行'}, 503)
        else await json(route, ok({...tokens, accessToken: 'recovered-access'}))
        return true
    })
    await login(page)
    await page.getByRole('button', {name: '新增资源', exact: true}).click()
    await page.getByRole('dialog').getByLabel('资源名称', {exact: true}).fill('未保存的真实资料')
    const run = () => page.evaluate(async () => {
        const path = '/src/stores/session.ts', session = (await import(path)).useSession()
        try { await session.refresh(session.accessToken); return session.accessToken }
        catch (e) { return (e as {code: string}).code }
    })
    expect(await run()).toBe('REFRESH_NOT_EXECUTED')
    await expect(page.getByRole('dialog').getByLabel('资源名称', {exact: true})).toHaveValue('未保存的真实资料')
    expect(await run()).toBe('recovered-access')
    expect(attempts).toBe(2)
    await expect(page).toHaveURL(/\/resources$/)
})

for (const revoked of [false, true]) {
    test(revoked ? '刷新凭据被撤销立即退出' : '刷新响应丢失保留表单并禁止重放凭据', async ({page}) => {
        let attempts = 0
        await install(page, async route => {
            if (new URL(route.request().url()).pathname !== '/api/v1/auth/refresh') return false
            attempts++
            if (revoked) await json(route, {code: 'SESSION_REVOKED', message: '登录状态已失效'}, 401)
            else await route.abort('failed')
            return true
        })
        await login(page)
        await page.getByRole('button', {name: '新增资源', exact: true}).click()
        await page.getByRole('dialog').getByLabel('资源名称', {exact: true}).fill('暂存内容')
        const refresh = () => page.evaluate(async () => {
            const path = '/src/stores/session.ts', session = (await import(path)).useSession()
            try { await session.refresh(session.accessToken) } catch (e) { return (e as {code: string}).code }
        })
        expect(await refresh()).toBe(revoked ? 'SESSION_REVOKED' : 'NETWORK_ERROR')
        if (revoked) await expect(page).toHaveURL(/\/login$/)
        else {
            await expect(page.getByRole('dialog').getByLabel('资源名称', {exact: true})).toHaveValue('暂存内容')
            expect(await refresh()).toBe('REFRESH_UNCERTAIN')
            expect(attempts).toBe(1)
        }
    })
}

test('资源提交断网后重试保持原幂等键，成功后关闭表单', async ({page}) => {
    const keys: string[] = []
    await install(page, async route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/admin/resources') return false
        keys.push(route.request().headers()['idempotency-key']!)
        if (keys.length === 1) await route.abort('failed')
        else await json(route, ok(resource(1)), 201)
        return true
    })
    await login(page)
    await page.getByRole('button', {name: '新增资源', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: '新增资源'})
    await dialog.getByLabel('资源名称', {exact: true}).fill('临时前端测试')
    await dialog.getByRole('button', {name: '保存资源', exact: true}).click()
    await expect(page.getByText('网络连接失败，请检查连接后重试', {exact: true})).toBeVisible()
    await dialog.getByRole('button', {name: '保存资源', exact: true}).click()
    await expect(dialog).toHaveCount(0)
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
})
