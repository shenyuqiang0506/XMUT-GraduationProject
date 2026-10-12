import {test, expect, type Page} from '@playwright/test'

const required = (name: string) => {
    const value = process.env[name];
    if (!value) throw new Error('Run this suite through catalog-smoke.py with XMUT_WEB_TEST=1');
    return value
}

async function login(page: Page, user: string, password: string) {
    await page.goto('/login');
    await page.locator('input[name=username]').fill(user);
    await page.locator('input[name=password]').fill(password);
    await page.getByRole('button', {name: '登录', exact: true}).click();
    await expect(page.getByRole('heading', {name: '资源目录', exact: true})).toBeVisible()
}

test('administrator: real login, one concurrent refresh, resource CRUD, users, import, logout', async ({page}) => {
    const prefix = required('TEST_PREFIX')
    await login(page, required('TEST_ADMIN_USERNAME'), required('TEST_ADMIN_PASSWORD'))
    await expect(page.getByRole('menuitem', {name: '账号与权限'})).toBeVisible()
    expect(await page.evaluate(() => Object.keys(localStorage).filter(k => /token|password/i.test(k)))).toEqual([])
    let refreshes = 0;
    const intercepted = new Set<string>()
    await page.route('**/api/v1/**', async route => {
        const url = new URL(route.request().url());
        if (url.pathname === '/api/v1/auth/refresh') refreshes++;
        if ((url.pathname === '/api/v1/resources' || url.pathname === '/api/v1/admin/approval-groups') && !intercepted.has(url.pathname)) {
            intercepted.add(url.pathname);
            await route.fulfill({
                status: 401,
                contentType: 'application/json',
                body: JSON.stringify({code: 'UNAUTHORIZED', message: '会话已过期', traceId: 'browser-test'})
            });
            return
        }
        await route.continue()
    })
    await page.getByRole('menuitem', {name: '账号与权限'}).click();
    await expect(page.getByRole('heading', {name: '账号与权限', exact: true})).toBeVisible()
    await page.getByRole('menuitem', {name: '资源目录'}).click();
    await expect(page.getByRole('heading', {name: '资源目录', exact: true})).toBeVisible()
    await expect.poll(() => intercepted.size).toBe(2);
    await expect.poll(() => refreshes).toBe(1)
    await page.getByRole('button', {name: '新增资源', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: '新增资源'})
    await expect(dialog.getByText('真实 REAL', {exact: true})).toBeVisible()
    await dialog.getByLabel('数据模式', {exact: true}).press('ArrowDown');
    await page.getByRole('option', {name: '演示 DEMO', exact: true}).click();
    await dialog.getByLabel('资源名称', {exact: true}).fill(prefix + ' Web resource')
    await dialog.getByLabel('来源系统', {exact: true}).fill(required('TEST_SOURCE'))
    await dialog.getByLabel('来源编号（保留前导零）', {exact: true}).fill('web0001')
    await dialog.getByRole('button', {name: '保存资源', exact: true}).click();
    await expect(dialog).not.toBeVisible()
    const row = page.getByRole('row').filter({hasText: prefix + ' Web resource'});
    await expect(row).toBeVisible();
    await expect(row).toContainText('未知')
    await row.getByRole('button', {name: '编辑', exact: true}).click();
    const edit = page.getByRole('dialog', {name: '编辑资源'});
    await edit.getByLabel('资源名称', {exact: true}).fill(prefix + ' Web revised');
    await edit.getByRole('button', {name: '保存资源', exact: true}).click();
    await expect(edit).not.toBeVisible();
    await expect(page.getByRole('row').filter({hasText: prefix + ' Web revised'})).toBeVisible()
    await page.getByRole('menuitem', {name: '账号与权限'}).click();
    await page.getByRole('button', {name: '创建账号', exact: true}).click();
    const create = page.getByRole('dialog', {name: '创建账号'});
    await create.getByLabel('账号', {exact: true}).fill(prefix + '_web');
    await create.getByLabel('姓名', {exact: true}).fill('DEMO Web account');
    await create.getByLabel('初始密码（12–72 字节）', {exact: true}).fill(required('TEST_USER_PASSWORD'));
    await create.getByRole('button', {name: '创建账号', exact: true}).click();
    await expect(create).not.toBeVisible();
    await expect(page.getByRole('row').filter({hasText: prefix + '_web'})).toContainText('学生')
    await page.getByRole('menuitem', {name: '资料导入'}).click();
    await expect(page.locator('#app').getByText('真实来源 REAL', {exact: true})).toBeVisible()
    await page.getByLabel('数据模式', {exact: true}).press('ArrowDown');
    await page.getByRole('option', {name: '演示资料 DEMO', exact: true}).click();
    await page.getByLabel('来源系统', {exact: true}).fill(required('TEST_SOURCE'))
    await page.getByTestId('import-file').setInputFiles({
        name: 'browser.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify([{sourceId: 'web-import', name: 'DEMO Web import', capacity: null}]))
    })
    await page.getByRole('button', {name: '预览导入', exact: true}).click();
    await expect(page.getByTestId('import-preview')).toBeVisible();
    await page.getByRole('button', {name: '确认写入目录', exact: true}).click();
    await expect(page.getByTestId('import-preview')).toContainText('已确认写入');
    await expect(page.getByRole('button', {name: '确认写入目录', exact: true})).toBeDisabled()
    await page.getByTestId('import-file').setInputFiles({
        name: 'invalid.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify([{sourceId: 'web-invalid'}]))
    });
    await page.getByRole('button', {name: '预览导入', exact: true}).click();
    await expect(page.getByTestId('import-preview')).toContainText('VALIDATION_ERROR');
    await expect(page.getByRole('button', {name: '确认写入目录', exact: true})).toBeDisabled()
    await page.getByRole('button', {name: '退出登录', exact: true}).click();
    await expect(page.locator('input[name=username]')).toBeVisible();
    await page.goto('/users');
    await expect(page).toHaveURL(/\/login$/)
})

test('student: available catalog and route protection', async ({page}) => {
    await login(page, required('TEST_STUDENT_USERNAME'), required('TEST_USER_PASSWORD'))
    await expect(page.getByRole('menuitem', {name: '账号与权限'})).toHaveCount(0);
    await expect(page.getByRole('menuitem', {name: '资料导入'})).toHaveCount(0);
    await expect(page.getByRole('button', {name: '新增资源'})).toHaveCount(0)
    // Preserve the in-memory session while navigating through the router, then verify the guard.
    await page.evaluate(() => {
        history.pushState({}, '', '/users');
        dispatchEvent(new PopStateEvent('popstate'))
    });
    await expect(page).toHaveURL(/\/resources$/)
    await expect(page.getByRole('row').filter({hasText: 'DEMO revised'})).toBeVisible()
})

test('resource administrator: group scope and denied account route', async ({page}) => {
    await login(page, required('TEST_MANAGER_USERNAME'), required('TEST_USER_PASSWORD'))
    await expect(page.getByRole('menuitem', {name: '资料导入'})).toBeVisible();
    await expect(page.getByRole('menuitem', {name: '账号与权限'})).toHaveCount(0)
    await expect(page.getByRole('row').filter({hasText: 'DEMO revised'})).toBeVisible();
    await expect(page.getByRole('row').filter({hasText: required('TEST_PREFIX') + ' Web revised'})).toHaveCount(0)
    await page.getByRole('menuitem', {name: '资料导入'}).click();
    await expect(page.getByRole('heading', {name: '资料导入', exact: true})).toBeVisible()
})
