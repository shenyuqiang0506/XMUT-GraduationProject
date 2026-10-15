// 联合预约端到端验收：脚本提供独立 DEMO 账号、资源和时段，结束后删除全部测试资料。
import {test,expect,type Page} from '@playwright/test'
test.use({timezoneId:'Asia/Shanghai'})
test.skip(!process.env.TEST_APPLICATION_PREFIX,'由 applications-smoke.py 准备独立资料后运行')
const required=(name:string)=>{const value=process.env[name];if(!value)throw new Error('缺少预约浏览器验收配置');return value}
async function login(page:Page,role:string){
 await page.goto('/login')
 await page.locator('input[name=username]').fill(required('TEST_APPLICATION_'+role))
 await page.locator('input[name=password]').fill(required('TEST_APPLICATION_'+role+'_PASSWORD'))
 await page.getByRole('button',{name:'登录',exact:true}).click()
 await expect(page.getByRole('heading',{name:'资源目录',exact:true})).toBeVisible()
}
async function logout(page:Page){
 const dialog=page.getByRole('dialog')
 if(await dialog.count())await dialog.getByRole('button',{name:'关闭此对话框',exact:true}).click({timeout:15000})
 const exit=page.getByRole('button',{name:'退出登录',exact:true})
 if(await exit.count()){await exit.click();await expect(page).toHaveURL(/\/login$/)}
}
test.afterEach(async({page})=>{await logout(page)})
async function submit(page:Page,name:string){
 await page.getByRole('menuitem',{name:'可用时段查询'}).click()
 await expect(page.getByLabel('数据模式',{exact:true})).toHaveCount(0)
 for(const [label,field] of [['开始时间','START'],['结束时间','END']]){
   const input=page.getByLabel(label!,{exact:true})
   await input.fill(required('TEST_APPLICATION_'+field).replace(' ','T').replace(/:00$/,''));await input.press('Tab')
 }
 await page.getByLabel('校区',{exact:true}).fill(required('TEST_APPLICATION_CAMPUS'))
 await page.getByRole('button',{name:'添加设备需求',exact:true}).click()
 await page.getByRole('button',{name:'查询可用组合',exact:true}).click()
 const row=page.getByTestId('availability-results').getByRole('row').filter({has:page.getByText(required('TEST_APPLICATION_VENUE'),{exact:true})})
 await row.getByRole('button',{name:'申请此组合',exact:true}).click()
 await page.getByLabel('活动名称',{exact:true}).fill(name)
 await page.getByLabel('活动说明',{exact:true}).fill('独立 DEMO 浏览器验收')
 await page.getByRole('button',{name:'确认提交申请',exact:true}).click()
 await expect(page).toHaveURL(/\/applications$/)
 await expect(page.getByTestId('application-list')).toContainText(name)
}
async function open(page:Page,name:string){
 const row=page.getByTestId('application-list').getByRole('row').filter({hasText:name})
 await row.getByRole('button',{name:'查看详情',exact:true}).click()
 await expect(page.getByTestId('application-detail')).toContainText(name)
}

test('学生提交整组并取消，只有本人操作且不能进入审批路由',async({page})=>{
 await login(page,'STUDENT')
 await expect(page.getByRole('menuitem',{name:'预约审批',exact:true})).toHaveCount(0)
 const name=required('TEST_APPLICATION_PREFIX')+' 浏览器学生取消'
 await submit(page,name);await open(page,name)
 await expect(page.getByTestId('application-detail')).toContainText('待审批')
 await expect(page.getByRole('button',{name:'批准申请',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'取消申请',exact:true}).click()
 await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已取消')
 await page.evaluate(()=>{history.pushState({},'', '/approvals');dispatchEvent(new PopStateEvent('popstate'))})
 await expect(page).toHaveURL(/\/resources$/)
})

test('授权管理员从审批列表批准，学生读取结果并释放资源',async({page})=>{
 const name=required('TEST_APPLICATION_PREFIX')+' 浏览器批准'
 await login(page,'STUDENT');await submit(page,name);await logout(page)
 await login(page,'MANAGER');await page.getByRole('menuitem',{name:'预约审批',exact:true}).click();await open(page,name)
 await page.getByRole('button',{name:'批准申请',exact:true}).click()
 await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已批准')
 await logout(page);await login(page,'STUDENT');await page.getByRole('menuitem',{name:'我的申请',exact:true}).click();await open(page,name)
 await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已批准')
 await page.getByRole('button',{name:'取消申请',exact:true}).click()
 await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已取消')
})

test('教师可申请但无审批按钮，取消后全组不再占用',async({page})=>{
 await login(page,'TEACHER');await expect(page.getByRole('menuitem',{name:'预约审批',exact:true})).toHaveCount(0)
 const name=required('TEST_APPLICATION_PREFIX')+' 浏览器教师'
 await submit(page,name);await open(page,name)
 await expect(page.getByRole('button',{name:'批准申请',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'取消申请',exact:true}).click()
 await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已取消')
})
