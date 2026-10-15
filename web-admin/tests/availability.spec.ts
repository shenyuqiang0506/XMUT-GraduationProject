// 可用性页面验收：由 availability-smoke.py 提供资源、时间范围和临时账号。
// 固定上海时区，保证浏览器表单时间与后端业务日期解释一致。
import {test,expect,type Page} from '@playwright/test'

// Playwright 在 Node.js 中执行；独立测试配置提供 process.env 与 Buffer 类型。
test.use({timezoneId:'Asia/Shanghai'})
test.skip(!process.env.TEST_AVAILABILITY_START,'由 availability-smoke.py 创建独立测试资料后执行')
// 浏览器测试结束后退出各自会话，不留下管理员登录状态。
test.afterEach(async({page})=>{
 const exit=page.getByRole('button',{name:'退出登录',exact:true})
 if(await exit.count()) await exit.click()
})
const required=(name:string)=>{const value=process.env[name];if(!value)throw new Error('缺少可用性浏览器验收环境');return value}
async function login(page:Page,user:string,password:string){
 await page.goto('/login')
 await page.locator('input[name=username]').fill(user)
 await page.locator('input[name=password]').fill(password)
 await page.getByRole('button',{name:'登录',exact:true}).click()
 await expect(page.getByRole('heading',{name:'资源目录',exact:true})).toBeVisible()
}

test('学生真实查询同组组合、查看日历且不能访问管理页',async({page})=>{
 await login(page,required('TEST_AVAILABILITY_STUDENT'),required('TEST_AVAILABILITY_PASSWORD'))
 await expect(page.getByRole('menuitem',{name:'开放与占用管理'})).toHaveCount(0)
 await page.getByRole('menuitem',{name:'可用时段查询'}).click()
 await expect(page.getByLabel('数据模式',{exact:true})).toHaveCount(0)
 await page.getByLabel('开始时间',{exact:true}).fill(required('TEST_AVAILABILITY_START').replace(' ', 'T').replace(/:00$/, ''))
 await page.getByLabel('开始时间',{exact:true}).press('Tab')
 await page.getByLabel('结束时间',{exact:true}).fill(required('TEST_AVAILABILITY_END').replace(' ', 'T').replace(/:00$/, ''))
 await page.getByLabel('结束时间',{exact:true}).press('Tab')
 await page.getByLabel('校区',{exact:true}).fill(required('TEST_AVAILABILITY_CAMPUS'))
 await page.getByRole('button',{name:'添加设备需求',exact:true}).click()
 await page.getByRole('button',{name:'查询可用组合',exact:true}).click()
 await expect(page.getByTestId('availability-results')).toContainText(required('TEST_AVAILABILITY_VENUE'))
 await page.getByRole('button',{name:'查看日历',exact:true}).click()
 await expect(page.getByTestId('availability-calendar')).toContainText('已确认完整覆盖')
 await page.evaluate(()=>{history.pushState({},'', '/rules');dispatchEvent(new PopStateEvent('popstate'))})
 await expect(page).toHaveURL(/\/resources$/)
})

test('管理员保存开放规则与统一策略，重新进入页面仍读取已保存参数',async({page})=>{
 await login(page,required('TEST_ADMIN_USERNAME'),required('TEST_ADMIN_PASSWORD'))
 await page.getByRole('menuitem',{name:'开放与占用管理'}).click()
 await expect(page.getByLabel('策略范围',{exact:true})).toHaveCount(0)
 await page.getByLabel('选择资源',{exact:true}).click()
 await page.getByRole('option',{name:required('TEST_AVAILABILITY_VENUE'),exact:true}).click()
 await page.getByRole('button',{name:'保存开放规则',exact:true}).click()
 await expect(page.getByText('开放规则已保存',{exact:true})).toBeVisible()
 await page.getByLabel('规则依据',{exact:true}).fill(required('TEST_AVAILABILITY_CAMPUS')+' 浏览器演示策略')
 await page.getByRole('button',{name:'保存并启用新版本',exact:true}).click()
 await expect(page.getByText('新策略版本已启用',{exact:true})).toBeVisible()
 await page.getByRole('menuitem',{name:'资料导入',exact:true}).click()
 await page.getByRole('menuitem',{name:'开放与占用管理',exact:true}).click()
 await expect(page.getByLabel('规则依据',{exact:true})).toHaveValue(required('TEST_AVAILABILITY_CAMPUS')+' 浏览器演示策略')
})

// 使用页面表单导入完整空快照，并确认修改参数后旧预览被作废。
test('管理员核对完整时间快照并确认，修改来源说明后需重新预览',async({page})=>{
 await login(page,required('TEST_ADMIN_USERNAME'),required('TEST_ADMIN_PASSWORD'))
 await page.getByRole('menuitem',{name:'资料导入'}).click()
 await expect(page.getByLabel('数据模式',{exact:true})).toHaveCount(0)
 await page.getByLabel('导入内容',{exact:true}).press('ArrowDown')
 await page.getByRole('option',{name:'来源占用快照',exact:true}).click()
 await page.getByLabel('来源系统',{exact:true}).fill(required('TEST_AVAILABILITY_SOURCE'))
 await page.getByLabel('覆盖完整性',{exact:true}).press('ArrowDown')
 await page.getByRole('option',{name:'已确认完整 COMPLETE',exact:true}).click()
 await page.getByLabel('明确的资源范围',{exact:true}).press('ArrowDown')
 await page.getByRole('option',{name:required('TEST_AVAILABILITY_VENUE')+' · venue',exact:true}).click()
 await page.getByLabel('明确的资源范围',{exact:true}).press('Escape')
 await page.getByLabel('覆盖开始',{exact:true}).fill(required('TEST_AVAILABILITY_START').replace(' ', 'T').replace(/:00$/, ''))
 await page.getByLabel('覆盖开始',{exact:true}).press('Tab')
 await page.getByLabel('覆盖结束',{exact:true}).fill(required('TEST_AVAILABILITY_END').replace(' ', 'T').replace(/:00$/, ''))
 await page.getByLabel('覆盖结束',{exact:true}).press('Tab')
 await page.getByLabel('来源采集时间',{exact:true}).fill(new Date(Date.now()-1000).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).replace(' ', 'T').replace(/:00$/, ''))
 await page.getByLabel('来源采集时间',{exact:true}).press('Tab')
 await page.getByLabel('来源依据',{exact:true}).fill('DEMO 浏览器验收，不代表学校制度')
 await page.getByTestId('import-file').setInputFiles({name:'empty-occupancies.json',mimeType:'application/json',buffer:Buffer.from('[]')})
 await page.getByRole('button',{name:'预览导入',exact:true}).click()
 await expect(page.getByTestId('import-preview')).toBeVisible()
 await page.getByLabel('来源依据',{exact:true}).fill('DEMO 浏览器验收修订，重新核对')
 await expect(page.getByTestId('import-preview')).toHaveCount(0)
 await page.getByRole('button',{name:'预览导入',exact:true}).click()
 await page.getByRole('button',{name:'确认写入来源时间数据',exact:true}).click()
 await expect(page.getByTestId('import-preview')).toContainText('已确认写入')
})
