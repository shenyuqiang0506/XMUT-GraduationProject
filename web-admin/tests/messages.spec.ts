// 独立 DEMO 服务与账号由 waitlists-messaging-smoke.py 准备；不在浏览器持久化访问令牌。
import {test,expect,type Page} from '@playwright/test'
test.use({timezoneId:'Asia/Shanghai',actionTimeout:10000})
test.skip(!process.env.TEST_MESSAGES_PREFIX,'需要候补与消息验收脚本准备独立资料')
const required=(name:string)=>{const value=process.env['TEST_MESSAGES_'+name];if(!value)throw new Error('缺少候补浏览器验收配置');return value}
async function login(page:Page){
 await page.goto('/login');await page.locator('input[name=username]').fill(required('TEACHER'))
 await page.locator('input[name=password]').fill(required('TEACHER_PASSWORD'));await page.getByRole('button',{name:'登录',exact:true}).click()
 await expect(page.getByRole('heading',{name:'资源目录',exact:true})).toBeVisible()
}
test.afterEach(async({page},info)=>{
 if(info.status!=='passed')return
 const dialog=page.getByRole('dialog');if(await dialog.count())await dialog.getByRole('button',{name:'关闭此对话框',exact:true}).click()
 const exit=page.getByRole('button',{name:'退出登录',exact:true});if(await exit.count())await exit.click()
})
test('候补链接读取最新状态，已确认记录可打开关联申请，教师无运维权限',async({page})=>{
 await login(page);await page.getByRole('menuitem',{name:'我的候补',exact:true}).click()
 await expect(page.getByTestId('waitlist-list')).toContainText(required('PREFIX'))
 await page.evaluate(id=>{history.pushState({},'', '/waitlists?id='+id);dispatchEvent(new PopStateEvent('popstate'))},required('WAITLIST_ID'))
 await expect(page.getByTestId('waitlist-detail')).toContainText('已转待审批申请')
 await expect(page.getByRole('button',{name:'确认候补并提交审批',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'查看关联申请',exact:true}).click()
 await expect(page.getByTestId('application-detail')).toContainText('已取消')
 await page.getByRole('dialog').getByRole('button',{name:'关闭此对话框',exact:true}).click()
 await expect(page.getByRole('menuitem',{name:'消息投递管理',exact:true})).toHaveCount(0)
 await page.evaluate(()=>{history.pushState({},'', '/event-delivery');dispatchEvent(new PopStateEvent('popstate'))})
 await expect(page).toHaveURL(/\/resources$/)
})
test('真实站内收件箱显示中文事件，标为已读后刷新未读计数',async({page})=>{
 await login(page);await page.getByRole('menuitem',{name:'站内通知',exact:true}).click()
 await expect(page.getByTestId('notifications-list')).toContainText('候补')
 const buttons=page.getByRole('button',{name:'标为已读',exact:true})
 await expect(buttons.first()).toBeVisible()
 const before=await buttons.count();await buttons.first().click()
 await expect(buttons).toHaveCount(before-1)
 await expect(page.getByRole('heading',{name:'站内通知',exact:true})).toBeVisible()
})
test('教师登记固定组合，等待整组机会后确认并取消新申请',async({page,request})=>{
 await login(page);await page.getByRole('menuitem',{name:'我的候补',exact:true}).click()
 await page.getByRole('button',{name:'登记固定组合候补',exact:true}).click()
 const dialog=page.getByRole('dialog')
 await page.getByLabel('数据模式',{exact:true}).press('ArrowDown');await page.getByRole('option',{name:'演示 DEMO',exact:true}).click()
 await page.getByLabel('候补场地',{exact:true}).click();await page.getByRole('option',{name:required('VENUE'),exact:true}).click()
 await page.getByLabel('配套设备',{exact:true}).press('ArrowDown');await page.getByRole('option',{name:required('EQ1'),exact:true}).click()
 await page.keyboard.press('Escape')
 for(const [label,field] of [['开始时间','START'],['结束时间','END']]){
  const input=page.getByLabel(label!,{exact:true});await input.fill(required(field!));await input.press('Tab')
 }
 const name=required('PREFIX')+' 浏览器登记候补'
 await page.getByLabel('活动名称',{exact:true}).fill(name)
 await page.getByRole('button',{name:'确认加入候补',exact:true}).click();await expect(dialog).toHaveCount(0)
 const row=page.getByTestId('waitlist-list').getByRole('row').filter({hasText:name})
 await expect(row).toContainText('排队中')
 // 测试资料占用由本人释放，模拟其他申请结束；不通过浏览器伪造候补状态或倒计时。
 const response=await request.post('http://127.0.0.1:8081/api/v1/auth/login',{data:{username:required('STUDENT'),password:required('STUDENT_PASSWORD'),deviceId:required('PREFIX')+'-web-release',deviceType:'WEB'}})
 expect(response.status()).toBe(200);const token=(await response.json()).data.accessToken
 const released=await request.post('http://127.0.0.1:8081/api/v1/applications/'+required('BLOCKER')+'/cancel',{
  headers:{Authorization:'Bearer '+token,'Idempotency-Key':required('PREFIX')+'-web-release'},data:{expectedVersion:0,reason:'DEMO 浏览器释放'}})
 expect(released.status()).toBe(200)
 await expect(async()=>{await page.getByRole('button',{name:'查询',exact:true}).click();await expect(row).toContainText('待本人确认')}).toPass({timeout:30000})
 await row.getByRole('button',{name:'查看详情',exact:true}).click()
 await page.getByRole('button',{name:'确认候补并提交审批',exact:true}).click()
 await expect(page.getByTestId('waitlist-detail')).toContainText('已转待审批申请')
 await page.getByRole('button',{name:'查看关联申请',exact:true}).click()
 await expect(page.getByTestId('application-detail')).toContainText('待审批')
 await page.getByRole('button',{name:'取消申请',exact:true}).click()
 await expect(page.getByTestId('application-detail')).toContainText('已取消')
})
