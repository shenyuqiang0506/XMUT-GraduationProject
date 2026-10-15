import {test,expect,type Page,type Route} from '@playwright/test'

// 由 ai-smoke.py 提供临时账号与真实服务；仅在指定读取请求上注入故障，其他响应均来自 dev API。
test.skip(!process.env.TEST_AI_PREFIX,'由 ai-smoke.py 准备隔离资料后运行')
test.use({timezoneId:'America/Los_Angeles'})
const required=(name:string)=>{const value=process.env[name];if(!value)throw new Error('缺少 AI 浏览器验收配置');return value}
async function forward(route:Route){
    const url=new URL(route.request().url())
    await route.fulfill({response:await route.fetch({url:required('TEST_AI_GATEWAY_BASE')+url.pathname.replace('/api/v1','')+url.search})})
}
async function login(page:Page){
    await test.step('登录真实账号并打开会话',async()=>{
    await page.goto('/login');await page.locator('input[name=username]').fill(required('TEST_AI_USER'))
    await page.locator('input[name=password]').fill(required('TEST_AI_PASSWORD'))
    await page.getByRole('button',{name:'登录',exact:true}).click()
    await expect(page.getByRole('heading',{name:'资源目录',exact:true})).toBeVisible()
    await page.getByRole('menuitem',{name:'智能资源查询',exact:true}).click()
    await expect(page.getByTestId('ai-conditions')).toBeVisible()
    })
    await test.step('选择本轮验收会话',async()=>{
    const label='会话 '+required('TEST_AI_CONVERSATION_ID')
    await page.locator('.el-select__wrapper').click({timeout:10000});await page.getByRole('option',{name:label,exact:true}).click({timeout:10000})
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：8')
    await expect(page.getByTestId('ai-plan')).toHaveCount(1)
    })
}
test.afterEach(async({page})=>{
    // 临时账号的全部会话由外层 finally 清理；移除转发器，避免失败后的在途请求覆盖主错误。
    await page.unrouteAll({behavior:'ignoreErrors'})
})

test('真实会话读取失败可降级，新建后列表故障仍将消息保存到新会话',async({page})=>{
    let failRead=false,failList=false
    const sent:string[]=[]
    await page.route('**/api/v1/**',async route=>{
        const {pathname}=new URL(route.request().url()),method=route.request().method()
        if(failRead&&method==='GET'&&pathname.endsWith('/messages')){
            failRead=false;await route.fulfill({status:503,json:{code:'SERVICE_UNAVAILABLE',message:'验收读取故障'}});return
        }
        if(failList&&method==='GET'&&pathname==='/api/v1/ai/conversations'){
            failList=false;await route.fulfill({status:503,json:{code:'SERVICE_UNAVAILABLE',message:'验收列表故障'}});return
        }
        if(method==='POST'&&pathname.endsWith('/messages'))sent.push(pathname)
        await forward(route)
    })
    await login(page);failRead=true
    await page.getByRole('button',{name:'读取会话结果',exact:true}).click()
    await expect(page.getByRole('alert')).toContainText('验收读取故障')
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：8');await expect(page.getByTestId('ai-plan')).toHaveCount(0)
    await page.getByRole('button',{name:'改用普通查询',exact:true}).click()
    await expect(page.getByLabel('开始时间',{exact:true})).toHaveValue(required('TEST_AI_DATE')+'T15:00')
    await expect(page.getByLabel('结束时间',{exact:true})).toHaveValue(required('TEST_AI_DATE')+'T17:30')
    await expect(page.getByLabel('活动人数',{exact:true})).toHaveValue('8')
    await page.getByRole('menuitem',{name:'智能资源查询',exact:true}).click()
    await expect(page.getByTestId('ai-plan')).toHaveCount(1)
    failList=true
    const createdResponse=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/v1/ai/conversations'&&response.request().method()==='POST')
    await page.getByRole('button',{name:'新建会话',exact:true}).click()
    const created=(await(await createdResponse).json()).data
    await expect(page.getByRole('alert')).toContainText('验收列表故障')
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：待确认')
    await page.getByLabel('查询需求',{exact:true}).fill('浏览器六人')
    await page.getByRole('button',{name:'发送需求',exact:true}).click()
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：6')
    expect(sent).toEqual(['/api/v1/ai/conversations/'+created.conversationId+'/messages'])
    // 页面刷新按现行凭据策略返回登录；离开再进入会话，从真实历史接口核对持久化结果。
    await page.getByRole('menuitem',{name:'资源目录',exact:true}).click()
    await page.getByRole('menuitem',{name:'智能资源查询',exact:true}).click()
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：6')
})

test('真实AI方案以2000字符说明提交普通申请并取消，2001字符在发送前拦截',async({page})=>{
    const writes:string[]=[]
    await page.route('**/api/v1/**',async route=>{
        if(new URL(route.request().url()).pathname==='/api/v1/applications'&&route.request().method()==='POST')writes.push(route.request().postDataJSON().description)
        await forward(route)
    })
    await login(page);await page.getByRole('button',{name:'核对并申请此方案',exact:true}).click()
    const dialog=page.getByRole('dialog',{name:'确认活动申请'}),description=dialog.getByLabel('活动说明',{exact:true})
    const name=required('TEST_AI_PREFIX')+' 浏览器确认'
    await dialog.getByLabel('活动名称',{exact:true}).fill(name)
    await expect(description).toHaveAttribute('maxlength','2000')
    await description.evaluate(element=>{(element as HTMLTextAreaElement).value='测'.repeat(2001);element.dispatchEvent(new Event('input',{bubbles:true}))})
    await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(dialog.getByRole('alert')).toContainText('活动说明不能超过 2000 字符');expect(writes).toHaveLength(0)
    await description.fill('测'.repeat(2000));await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(page).toHaveURL(/\/applications$/);expect(writes.map(value=>value.length)).toEqual([2000])
    await page.getByTestId('application-list').getByRole('row').filter({hasText:name}).getByRole('button',{name:'查看详情',exact:true}).click()
    await expect(page.getByTestId('application-detail')).toContainText(name)
    await page.getByRole('button',{name:'取消申请',exact:true}).click()
    await expect(page.getByTestId('application-detail').getByRole('heading',{level:2})).toContainText('已取消')
})
