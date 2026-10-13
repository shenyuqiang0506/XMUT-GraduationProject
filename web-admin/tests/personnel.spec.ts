import {test, expect} from '@playwright/test'

// 由 personnel-smoke.py 注入本轮临时工号与密码，普通前端测试不使用固定账号。
test.skip(!process.env.TEST_PERSONNEL_TEACHER, '由人员账号验收脚本准备测试账户')

test('教师通过工号登录，仅显示普通用户功能且管理路由不可进入', async ({page}) => {
    await page.goto('/login')
    await page.locator('input[name=username]').fill(process.env.TEST_PERSONNEL_TEACHER!)
    await page.locator('input[name=password]').fill(process.env.TEST_PERSONNEL_PASSWORD!)
    await page.getByRole('button', {name: '登录', exact: true}).click()
    await expect(page.getByRole('heading', {name: '资源目录', exact: true})).toBeVisible()
    for (const name of ['账号与权限', '资料导入', '开放与占用管理']) {
        await expect(page.getByRole('menuitem', {name, exact: true})).toHaveCount(0)
    }
    await expect(page.getByRole('button', {name: '新增资源', exact: true})).toHaveCount(0)
    await page.getByRole('menuitem', {name: '可用时段查询', exact: true}).click()
    await expect(page).toHaveURL(/\/availability$/)
    for (const path of ['/users', '/imports', '/rules']) {
        await page.evaluate(path => { history.pushState({}, '', path); dispatchEvent(new PopStateEvent('popstate')) }, path)
        await expect(page).toHaveURL(/\/resources$/)
    }
    await page.getByRole('button', {name: '退出登录', exact: true}).click()
    await expect(page).toHaveURL(/\/login$/)
})

test('管理员创建表单区分学号和工号并显示教师角色', async ({page}) => {
    await page.goto('/login')
    await page.locator('input[name=username]').fill(process.env.TEST_ADMIN_USERNAME!)
    await page.locator('input[name=password]').fill(process.env.TEST_ADMIN_PASSWORD!)
    await page.getByRole('button', {name: '登录', exact: true}).click()
    await page.getByRole('menuitem', {name: '账号与权限', exact: true}).click()
    await page.getByRole('button', {name: '创建账号', exact: true}).click()
    const dialog = page.getByRole('dialog', {name: '创建账号'})
    await expect(dialog.getByLabel('学号', {exact: true})).toBeVisible()
    await dialog.getByText('学生', {exact: true}).click()
    await dialog.getByText('教师', {exact: true}).click()
    await expect(dialog.getByLabel('工号', {exact: true})).toBeVisible()
    await dialog.getByRole('button', {name: '关闭此对话框'}).click()
    await page.getByRole('button', {name: '退出登录', exact: true}).click()
})
