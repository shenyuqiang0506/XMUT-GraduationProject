<script setup lang="ts">
/**
 * 本人智能查询会话，显示服务器确认的条件与真实资源方案。
 * 断网后保留 clientMessageId、原文本和条件版本，先读结果或重试原消息，不能重复消耗模型额度。
 * AI 卡片只进入普通确认表单，申请仍调用 /applications 并接受当前冲突检查。
 */
import {ref, onMounted, onBeforeUnmount} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api, ApiError, explain, mutationKeys} from '../api/client'
import type {ApplicationDetail} from '../types'

interface Conditions {
  campus?: string; preferredBuilding?: string; date?: string; startLocal?: string; endLocal?: string;
  startAt?: string; endAt?: string; durationMinutes?: number; participantCount?: number;
  requiredFeatures?: string[]; equipmentRequirements?: {type: string; count: number}[]
}
interface Conversation {conversationId: string; dataMode: string; status: string; version: number; conditions: Conditions; processing: boolean}
interface Plan {venueId: string; venueName: string; equipmentIds: string[]; resourceIds: string[]; startAt: string; endAt: string;
  participantCount: number; requiredFeatures: string[]; checkedAt: string; dataMode: string; policyVersion: number; ruleBasis: string;
  resources: {id: string; name: string; resourceType: string; equipmentType: string | null}[]}
interface Result {messageId?: string; conditionVersion?: number; status?: string; reply?: string; conditions?: Conditions; plans?: Plan[]; errorCode?: string}
interface Message {messageId: string; clientMessageId: string; text: string; reply: string; status: string}
interface History {conversation: Conversation; items: Message[]; latest: Result}
const router=useRouter(), keys=mutationKeys()
const sessions=ref<Conversation[]>([]), selected=ref(''), current=ref<Conversation>(), messages=ref<Message[]>([]), result=ref<Result>({})
const text=ref(''), busy=ref(false), error=ref(''), booking=ref(false), chosen=ref<Plan>(), activity=ref(''), description=ref('')
const pending=ref<{conversationId: string; body: {text: string; clientMessageId: string; expectedConditionVersion: number}}>()
let readRequest=0
onBeforeUnmount(() => {readRequest++})
const labels: Record<string,string>={PROJECTOR:'投影',WHITEBOARD:'白板',SOUND_SYSTEM:'音响',MICROPHONE:'话筒',DESK:'桌椅',NETWORK:'网络',COMPUTER:'电脑'}
function display(value?: string) {return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'待确认'}

async function list() {sessions.value=(await api<{items:Conversation[]}>('/ai/conversations')).items}
async function load() {
  const id=selected.value, request=++readRequest
  if(!id)return
  busy.value=true;error.value='';current.value=undefined;result.value={};messages.value=[]
  try {
    const history=await api<History>('/ai/conversations/'+id+'/messages')
    if(request!==readRequest||id!==selected.value)return
    current.value=history.conversation;messages.value=history.items;result.value=history.latest
    if(pending.value?.conversationId===id&&history.items.some(item => item.clientMessageId===pending.value?.body.clientMessageId&&item.status!=='PROCESSING')) {
      pending.value=undefined;text.value=''
    }
  } catch(e) {if(request===readRequest)error.value=explain(e)}
  finally {if(request===readRequest)busy.value=false}
}
async function create() {
  if(busy.value||pending.value)return
  busy.value=true;error.value=''
  const body={dataMode:'REAL'}
  try {
    const created=await api<Conversation>('/ai/conversations','POST',body,keys.key('AI_CREATE',body))
    keys.done('AI_CREATE',body);selected.value=created.conversationId;text.value='';await list();await load()
  } catch(e) {error.value=explain(e)} finally {busy.value=false}
}
onMounted(async () => {
  busy.value=true
  try {await list();if(sessions.value.length){selected.value=sessions.value[0]!.conversationId;await load()}}
  catch(e){error.value=explain(e)} finally {busy.value=false}
})
async function send() {
  if(busy.value||!current.value||current.value.status!=='ACTIVE')return
  if(!pending.value) {
    if(!text.value.trim()){error.value='请描述查询需求';return}
    pending.value={conversationId:current.value.conversationId,body:{text:text.value.trim(),clientMessageId:crypto.randomUUID(),expectedConditionVersion:current.value.version}}
  }
  const request=pending.value
  busy.value=true;error.value=''
  try {
    // AI 处理预算十秒，另留实时认证及两次短事务的余量；超时后仍使用原消息编号恢复。
    await api<Result>('/ai/conversations/'+request.conversationId+'/messages','POST',request.body,undefined,20000)
    pending.value=undefined;text.value='';await load()
  } catch(e) {
    error.value=explain(e)
    // 确定性拒绝不重放旧条件；在途、限流、网络或服务故障需要保留原消息并读取处理结果。
    if(e instanceof ApiError&&e.status>=400&&e.status<500&&![409,429].includes(e.status))pending.value=undefined
    if(e instanceof ApiError&&['AI_MESSAGE_ID_REUSED','AI_CONVERSATION_CLOSED','AI_RATE_LIMITED','BUSINESS_RATE_LIMITED'].includes(e.code))pending.value=undefined
    if(e instanceof ApiError&&e.code==='VERSION_CONFLICT'){pending.value=undefined;await load();error.value='会话条件已更新，请核对当前条件后重新发送'}
  } finally {busy.value=false}
}
async function close() {
  if(busy.value||!current.value||pending.value)return
  busy.value=true;error.value=''
  try {
    await api('/ai/conversations/'+current.value.conversationId+'/close','POST',{expectedConditionVersion:current.value.version})
    selected.value='';current.value=undefined;messages.value=[];result.value={};await list()
  } catch(e){error.value=explain(e)} finally{busy.value=false}
}
function prepare(plan: Plan){chosen.value=plan;activity.value='';description.value='';booking.value=true;error.value=''}
async function submit() {
  const plan=chosen.value
  if(busy.value||!plan)return
  if(!activity.value.trim()){error.value='请填写活动名称';return}
  const body={activityName:activity.value.trim(),description:description.value.trim()||null,participantCount:plan.participantCount,
    startAt:plan.startAt,endAt:plan.endAt,venueId:plan.venueId,equipmentIds:[...plan.equipmentIds],requiredFeatures:[...plan.requiredFeatures]}
  busy.value=true;error.value=''
  try {
    await api<ApplicationDetail>('/applications','POST',body,keys.key('AI_APPLICATION',body))
    keys.done('AI_APPLICATION',body);booking.value=false;ElMessage.success('申请已提交，等待审批');await router.push('/applications')
  } catch(e){error.value=explain(e)}finally{busy.value=false}
}
function ordinary() {
  const conditions=current.value?.conditions
  // 普通查询页面接收可编辑条件，进入后仍需用户点击查询，不沿用旧方案作为实时可用性。
  router.push({path:'/availability',query:conditions?{intent:JSON.stringify(conditions),dataMode:current.value!.dataMode}:{}})
}
</script>
<template>
  <h1>智能资源查询</h1>
  <p class="lead">描述人数、时间和设备需求，核对条件后选择方案。智能查询不会直接创建预约。</p>
  <p v-if="error&&!booking" role="alert" class="error">{{error}}</p>
  <div class="card">
    <div class="toolbar">
      <el-select v-model="selected" placeholder="选择本人会话" :disabled="busy||!!pending" @change="load">
        <el-option v-for="item in sessions" :key="item.conversationId" :value="item.conversationId"
                   :label="'会话 '+item.conversationId+(item.dataMode==='DEMO'?' · 测试资料':'')"/>
      </el-select>
      <el-button :disabled="busy||!!pending" @click="create">新建会话</el-button>
      <el-button :disabled="busy||!selected" @click="load">读取会话结果</el-button>
      <el-button :disabled="busy||!current||!!pending||current.processing" @click="close">关闭会话</el-button>
      <el-button @click="ordinary">改用普通查询</el-button>
    </div>
    <p v-if="!current" class="small">先新建会话，或选择已有会话继续查询。</p>
    <template v-else>
      <p v-if="current.dataMode==='DEMO'" class="small">当前会话使用隔离测试资料，不代表真实资源情况。</p>
      <div v-for="item in messages" :key="item.messageId" class="card" data-testid="ai-message">
        <p><strong>你的需求：</strong>{{item.text}}</p><p><strong>查询回复：</strong>{{item.reply}}</p>
      </div>
      <el-form label-position="top" @submit.prevent="send">
        <el-form-item label="查询需求"><el-input v-model="text" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="busy||!!pending"
          placeholder="例如：明天下午两点开始，六个人讨论两小时，需要投影"/></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="current.processing&&!pending">{{pending?'重试原消息':'发送需求'}}</el-button>
      </el-form>
      <p v-if="pending" class="small">上次消息结果尚未确认，已保留原消息编号。可以重试原消息或读取会话结果。</p>
      <p v-if="current.processing" role="status">消息正在处理，可稍后读取会话结果。</p>
      <h2>当前条件（上海时间）</h2>
      <p data-testid="ai-conditions">日期：{{current.conditions.date||'待确认'}}；开始：{{current.conditions.startLocal||'待确认'}}；
        结束：{{current.conditions.endLocal||(current.conditions.durationMinutes?current.conditions.durationMinutes+' 分钟':'待确认')}}；人数：{{current.conditions.participantCount||'待确认'}}</p>
      <p>校区：{{current.conditions.campus||'未限定'}}；楼栋：{{current.conditions.preferredBuilding||'未限定'}}；
        设施：{{current.conditions.requiredFeatures?.map(f=>labels[f]||f).join('、')||'无额外要求'}}</p>
      <p>设备：{{current.conditions.equipmentRequirements?.map(n=>n.type+' × '+n.count).join('、')||'无额外要求'}}</p>
      <el-alert v-if="result.status==='FAILED'" type="warning" :closable="false" :title="result.reply||'智能查询暂不可用，可改用普通查询。'"/>
      <div v-for="plan in result.plans||[]" :key="plan.venueId" class="card" data-testid="ai-plan">
        <h2>{{plan.venueName}}</h2><p>{{display(plan.startAt)}} 至 {{display(plan.endAt)}} · {{plan.participantCount}} 人</p>
        <p>设备：{{plan.resources.filter(r=>r.resourceType==='EQUIPMENT').map(r=>r.name).join('、')||'无需单件设备'}}</p>
        <p class="small">查询时已确认完整覆盖 · 核查时间 {{display(plan.checkedAt)}} · 规则版本 {{plan.policyVersion}}</p>
        <p class="small">规则依据：{{plan.ruleBasis}}</p>
        <el-button type="primary" :disabled="busy||!!pending" @click="prepare(plan)">核对并申请此方案</el-button>
      </div>
    </template>
  </div>
  <el-dialog v-model="booking" title="确认活动申请" width="600px" :close-on-click-modal="false">
    <p v-if="chosen">{{chosen.venueName}} · {{display(chosen.startAt)}} 至 {{display(chosen.endAt)}} · {{chosen.participantCount}} 人</p>
    <p>提交会重新检查整个场地与设备组合；方案生成不代表预约成功，之后仍需审批。</p>
    <p v-if="error" role="alert" class="error">{{error}}</p>
    <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
      <el-form-item label="活动名称"><el-input v-model="activity" maxlength="200"/></el-form-item>
      <el-form-item label="活动说明"><el-input v-model="description" type="textarea" maxlength="4000"/></el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy">确认提交申请</el-button>
    </el-form>
  </el-dialog>
</template>
