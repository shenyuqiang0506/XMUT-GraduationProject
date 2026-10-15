import {test,expect,type Page,type Route} from '@playwright/test'

// 浏览器流程使用明确模拟 API；真实 AI/预约事务另由 ai-smoke 验收，不写本机数据库。
test.use({timezoneId:'America/Los_Angeles'})
const ok=(data:unknown)=>({code:'OK',data,message:'成功',traceId:'ai-browser-test'})
const conditions={date:'2026-10-15',startLocal:'14:00',durationMinutes:120,participantCount:8,
    startAt:'2026-10-15T14:00:00+08:00',endAt:'2026-10-15T16:00:00+08:00',requiredFeatures:['PROJECTOR'],equipmentRequirements:[]}
const conversation={conversationId:'1',dataMode:'REAL',status:'ACTIVE',version:0,conditions,processing:false}
const plan={venueId:'11',venueName:'已核查场地',equipmentIds:['12'],resourceIds:['11','12'],startAt:conditions.startAt,endAt:conditions.endAt,
    participantCount:8,requiredFeatures:['PROJECTOR'],checkedAt:'2026-10-14T10:00:00+08:00',dataMode:'REAL',policyVersion:1,ruleBasis:'测试已确认规则',
    resources:[{id:'11',name:'已核查场地',resourceType:'VENUE'},{id:'12',name:'配套设备',resourceType:'EQUIPMENT',equipmentType:'话筒'}]}
async function json(route:Route,data:unknown,status=200){await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)})}
async function install(page:Page,handler:(route:Route)=>Promise<boolean>,admin=false){
    await page.route('**/api/v1/**',async route=>{
        if(await handler(route))return
        const path=new URL(route.request().url()).pathname
        if(path==='/api/v1/auth/login')return json(route,ok({accessToken:'ai-test-access',refreshToken:'ai-test-refresh',expiresIn:900}))
        if(path==='/api/v1/users/me')return json(route,ok({userId:'1',sessionId:'1',username:'ai-test',displayName:'智能查询测试',status:'ACTIVE',authVersion:0,roles:admin?['SYSTEM_ADMIN']:['STUDENT']}))
        if(path==='/api/v1/resources'||path==='/api/v1/applications')return json(route,ok({items:[],total:0,page:1,size:20}))
        if(path==='/api/v1/admin/approval-groups')return json(route,ok({items:[]}))
        if(path==='/api/v1/ai/conversations')return json(route,ok({items:[conversation]}))
        return json(route,{code:'UNEXPECTED_TEST_REQUEST',message:'未预期的测试请求'},500)
    })
}
async function login(page:Page){
    await page.goto('/login');await page.locator('input[name=username]').fill('ai-test');await page.locator('input[name=password]').fill('test-password')
    await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('heading',{name:'资源目录',exact:true})).toBeVisible()
}
async function openAi(page:Page){
    await login(page);await page.getByRole('menuitem',{name:'智能资源查询',exact:true}).click()
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：8')
}

test('AI 方案不会自动创建申请，冲突后保留表单并复用普通申请幂等键',async({page})=>{
    const writes:{key:string;body:unknown}[]=[]
    await install(page,async route=>{
        const path=new URL(route.request().url()).pathname
        if(path.endsWith('/ai/conversations/1/messages')){
            await json(route,ok({conversation,items:[],latest:{status:'READY',conditions,plans:[plan]}}));return true
        }
        if(path==='/api/v1/applications'&&route.request().method()==='POST'){
            writes.push({key:route.request().headers()['idempotency-key']!,body:route.request().postDataJSON()})
            if(writes.length===1)await json(route,{code:'RESOURCE_CONFLICT',message:'场地已被占用'},409)
            else await json(route,ok({applicationId:'99',status:'PENDING_APPROVAL'}),201)
            return true
        }
        return false
    })
    await openAi(page);await expect(page.getByTestId('ai-plan')).toContainText('配套设备');expect(writes).toHaveLength(0)
    await page.getByRole('button',{name:'核对并申请此方案',exact:true}).click();expect(writes).toHaveLength(0)
    const dialog=page.getByRole('dialog',{name:'确认活动申请'})
    await dialog.getByLabel('活动名称',{exact:true}).fill('用户确认的活动')
    await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(dialog.getByRole('alert')).toContainText('场地已被占用')
    await expect(dialog.getByLabel('活动名称',{exact:true})).toHaveValue('用户确认的活动')
    await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(page).toHaveURL(/\/applications$/);expect(writes).toHaveLength(2)
    expect(writes[1]?.key).toBe(writes[0]?.key)
    expect(writes[0]?.body).toMatchObject({venueId:'11',equipmentIds:['12'],participantCount:8,startAt:conditions.startAt,endAt:conditions.endAt})
})

test('AI 消息断网后保留原编号与版本，重试不生成第二条消息',async({page})=>{
    const sent:{clientMessageId:string;expectedConditionVersion:number;text:string}[]=[]
    await install(page,async route=>{
        if(!new URL(route.request().url()).pathname.endsWith('/ai/conversations/1/messages'))return false
        if(route.request().method()==='POST'){
            sent.push(route.request().postDataJSON())
            if(sent.length===1)await route.abort('failed')
            else await json(route,ok({status:'NEED_CLARIFICATION',conditionVersion:1,conditions,plans:[]}))
        }else await json(route,ok({conversation:{...conversation,version:sent.length>1?1:0},items:[],latest:{}}))
        return true
    })
    await openAi(page);await page.getByLabel('查询需求',{exact:true}).fill('人数改成八')
    await page.getByRole('button',{name:'发送需求',exact:true}).click()
    await expect(page.getByRole('alert').filter({hasText:'网络连接失败'})).toBeVisible()
    await expect(page.getByLabel('查询需求',{exact:true})).toBeDisabled()
    await page.getByRole('button',{name:'重试原消息',exact:true}).click()
    await expect(page.getByLabel('查询需求',{exact:true})).toHaveValue('')
    expect(sent).toHaveLength(2);expect(sent[1]).toEqual(sent[0])
})

test('模型失败可转普通筛选，海外浏览器沿用上海时刻与原人数',async({page})=>{
    await install(page,async route=>{
        if(!new URL(route.request().url()).pathname.endsWith('/ai/conversations/1/messages'))return false
        await json(route,ok({conversation,items:[],latest:{status:'FAILED',conditions,plans:[],reply:'已保留条件，可改用普通查询'}}));return true
    })
    await openAi(page);await expect(page.getByRole('alert').filter({hasText:'已保留条件'})).toBeVisible()
    await page.getByRole('button',{name:'改用普通查询',exact:true}).click()
    await expect(page).toHaveURL(/\/availability\?/)
    await expect(page.getByLabel('开始时间',{exact:true})).toHaveValue('2026-10-15T14:00')
    await expect(page.getByLabel('结束时间',{exact:true})).toHaveValue('2026-10-15T16:00')
    await expect(page.getByLabel('活动人数',{exact:true})).toHaveValue('8')
})

test('纯管理员不显示 AI 入口，手动切换路由也被角色守卫拦截',async({page})=>{
    let aiRequests=0
    await install(page,async route=>{if(new URL(route.request().url()).pathname.includes('/ai/'))aiRequests++;return false},true)
    await login(page);await expect(page.getByRole('menuitem',{name:'智能资源查询',exact:true})).toHaveCount(0)
    await page.evaluate(async()=>{const path='/src/router/index.ts';await(await import(path)).router.push('/ai')})
    await expect(page).toHaveURL(/\/resources$/);expect(aiRequests).toBe(0)
})

test('创建成功后列表失败仍切换到新会话，消息不会写入旧会话',async({page})=>{
    let created=false
    const sent:string[]=[]
    const fresh={...conversation,conversationId:'2',conditions:{requiredFeatures:[],equipmentRequirements:[]}}
    await install(page,async route=>{
        const path=new URL(route.request().url()).pathname,method=route.request().method()
        if(path==='/api/v1/ai/conversations'){
            if(method==='POST'){created=true;await json(route,ok(fresh),201)}
            else if(created)await json(route,{code:'DEPENDENCY_UNAVAILABLE',message:'会话列表暂不可用'},503)
            else await json(route,ok({items:[conversation]}))
            return true
        }
        if(/\/ai\/conversations\/[12]\/messages$/.test(path)){
            if(method==='POST'){sent.push(path);await json(route,ok({status:'NEED_CLARIFICATION'}))}
            else await json(route,ok({conversation:path.includes('/2/')?fresh:conversation,items:[],latest:path.includes('/2/')?{}:{status:'READY',plans:[plan]}}))
            return true
        }
        return false
    })
    await openAi(page);await expect(page.getByTestId('ai-plan')).toBeVisible()
    await page.getByRole('button',{name:'新建会话',exact:true}).click()
    await expect(page.getByRole('alert')).toContainText('会话列表暂不可用')
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：待确认')
    await expect(page.getByTestId('ai-plan')).toHaveCount(0)
    await page.getByLabel('查询需求',{exact:true}).fill('新会话六个人')
    await page.getByRole('button',{name:'发送需求',exact:true}).click()
    await expect.poll(()=>sent.length).toBe(1);expect(sent).toEqual(['/api/v1/ai/conversations/2/messages'])
})

test('同一会话读取失败保留条件用于普通查询，同时撤下需要重新核查的方案',async({page})=>{
    let reads=0
    await install(page,async route=>{
        if(!new URL(route.request().url()).pathname.endsWith('/ai/conversations/1/messages'))return false
        if(++reads===1)await json(route,ok({conversation,items:[{messageId:'1',clientMessageId:'old',text:'八人讨论',reply:'已确认',status:'READY'}],latest:{status:'READY',plans:[plan]}}))
        else await json(route,{code:'DEPENDENCY_UNAVAILABLE',message:'会话读取暂不可用'},503)
        return true
    })
    await openAi(page);await expect(page.getByTestId('ai-plan')).toBeVisible()
    await page.getByRole('button',{name:'读取会话结果',exact:true}).click()
    await expect(page.getByRole('alert')).toContainText('会话读取暂不可用')
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：8')
    await expect(page.getByTestId('ai-message')).toContainText('八人讨论')
    await expect(page.getByTestId('ai-plan')).toHaveCount(0)
    await page.getByRole('button',{name:'改用普通查询',exact:true}).click()
    await expect(page.getByLabel('开始时间',{exact:true})).toHaveValue('2026-10-15T14:00')
    await expect(page.getByLabel('结束时间',{exact:true})).toHaveValue('2026-10-15T16:00')
    await expect(page.getByLabel('活动人数',{exact:true})).toHaveValue('8')
})

test('发送成功后的历史读取失败仍保留服务器刚确认的条件和版本',async({page})=>{
    let reads=0
    const versions:number[]=[]
    await install(page,async route=>{
        if(!new URL(route.request().url()).pathname.endsWith('/ai/conversations/1/messages'))return false
        if(route.request().method()==='POST'){
            versions.push(route.request().postDataJSON().expectedConditionVersion)
            await json(route,ok({status:'NEED_CLARIFICATION',conditions:{...conditions,participantCount:10},conditionVersion:versions.length,plans:[]}))
        }else if(++reads===1)await json(route,ok({conversation,items:[],latest:{status:'READY',plans:[plan]}}))
        else await json(route,{code:'DEPENDENCY_UNAVAILABLE',message:'会话读取暂不可用'},503)
        return true
    })
    await openAi(page);await page.getByLabel('查询需求',{exact:true}).fill('人数改成十人')
    await page.getByRole('button',{name:'发送需求',exact:true}).click()
    await expect(page.getByRole('alert')).toContainText('会话读取暂不可用')
    await expect(page.getByTestId('ai-conditions')).toContainText('人数：10');await expect(page.getByTestId('ai-plan')).toHaveCount(0)
    await page.getByLabel('查询需求',{exact:true}).fill('十个人')
    await page.getByRole('button',{name:'发送需求',exact:true}).click()
    await expect.poll(()=>versions.length).toBe(2);expect(versions).toEqual([0,1])
    await expect(page.getByRole('button',{name:'发送需求',exact:true})).toBeEnabled()
    await page.getByRole('button',{name:'改用普通查询',exact:true}).click()
    await expect(page.getByLabel('活动人数',{exact:true})).toHaveValue('10')
})

test('AI 申请说明限制与后端一致，绕过输入限制的超长说明也不会发送',async({page})=>{
    const descriptions:string[]=[]
    await install(page,async route=>{
        const path=new URL(route.request().url()).pathname
        if(path.endsWith('/ai/conversations/1/messages')){await json(route,ok({conversation,items:[],latest:{status:'READY',plans:[plan]}}));return true}
        if(path==='/api/v1/applications'&&route.request().method()==='POST'){
            descriptions.push(route.request().postDataJSON().description)
            await json(route,ok({applicationId:'99',status:'PENDING_APPROVAL'}),201);return true
        }
        return false
    })
    await openAi(page);await page.getByRole('button',{name:'核对并申请此方案',exact:true}).click()
    const dialog=page.getByRole('dialog',{name:'确认活动申请'}),input=dialog.getByLabel('活动说明',{exact:true})
    await expect(input).toHaveAttribute('maxlength','2000')
    await dialog.getByLabel('活动名称',{exact:true}).fill('长度边界活动')
    await input.evaluate(element=>{(element as HTMLTextAreaElement).value='测'.repeat(2001);element.dispatchEvent(new Event('input',{bubbles:true}))})
    await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(dialog.getByRole('alert')).toContainText('活动说明不能超过 2000 字符');expect(descriptions).toHaveLength(0)
    await input.fill('测'.repeat(2000));await dialog.getByRole('button',{name:'确认提交申请',exact:true}).click()
    await expect(page).toHaveURL(/\/applications$/);expect(descriptions.map(value=>value.length)).toEqual([2000])
})
