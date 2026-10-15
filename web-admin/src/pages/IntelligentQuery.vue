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
import {DATA_MODE} from '../api/dataMode'
import type {ApplicationDetail} from '../types'
import AppIcon from '../components/AppIcon.vue'

interface Conditions {
  campus?: string;
  preferredBuilding?: string;
  date?: string;
  startLocal?: string;
  endLocal?: string;
  startAt?: string;
  endAt?: string;
  durationMinutes?: number;
  participantCount?: number;
  requiredFeatures?: string[];
  equipmentRequirements?: { type: string; count: number }[]
}

interface Conversation {
  conversationId: string;
  dataMode: string;
  status: string;
  version: number;
  conditions: Conditions;
  processing: boolean
}

interface Plan {
  venueId: string;
  venueName: string;
  equipmentIds: string[];
  resourceIds: string[];
  startAt: string;
  endAt: string;
  participantCount: number;
  requiredFeatures: string[];
  checkedAt: string;
  dataMode: string;
  policyVersion: number;
  ruleBasis: string;
  resources: { id: string; name: string; resourceType: string; equipmentType: string | null }[]
}

interface Result {
  messageId?: string;
  conditionVersion?: number;
  status?: string;
  reply?: string;
  conditions?: Conditions;
  plans?: Plan[];
  errorCode?: string
}

interface Message {
  messageId: string;
  clientMessageId: string;
  text: string;
  reply: string;
  status: string
}

interface History {
  conversation: Conversation;
  items: Message[];
  latest: Result
}

const router = useRouter(), keys = mutationKeys()
const sessions = ref<Conversation[]>([]), selected = ref(''), current = ref<Conversation>(),
    messages = ref<Message[]>([]), result = ref<Result>({})
const text = ref(''), busy = ref(false), error = ref(''), booking = ref(false), chosen = ref<Plan>(),
    activity = ref(''), description = ref('')
const pending = ref<{
  conversationId: string;
  body: { text: string; clientMessageId: string; expectedConditionVersion: number }
}>()
let readRequest = 0
const descriptionLimit = 2000
onBeforeUnmount(() => {
  readRequest++
})
const labels: Record<string, string> = {
  PROJECTOR: '投影',
  WHITEBOARD: '白板',
  SOUND_SYSTEM: '音响',
  MICROPHONE: '话筒',
  DESK: '桌椅',
  NETWORK: '网络',
  COMPUTER: '电脑'
}

function display(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'}) : '待确认'
}

async function list() {
  sessions.value = (await api<{
    items: Conversation[]
  }>('/ai/conversations')).items.filter(item => item.dataMode === DATA_MODE)
}

async function load() {
  const id = selected.value, request = ++readRequest
  if (!id) return
  busy.value = true;
  error.value = '';
  result.value = {};
  chosen.value = undefined;
  booking.value = false
  // 同一会话读取失败时仍可携带已确认条件降级查询；切换会话时不借用旧条件。
  if (current.value?.conversationId !== id) {
    current.value = undefined;
    messages.value = []
  }
  try {
    const history = await api<History>('/ai/conversations/' + id + '/messages')
    if (request !== readRequest || id !== selected.value) return
    if (history.conversation.conversationId !== id) throw new Error('会话读取结果不匹配，请重新读取')
    current.value = history.conversation;
    messages.value = history.items;
    result.value = history.latest
    if (pending.value?.conversationId === id && history.items.some(item => item.clientMessageId === pending.value?.body.clientMessageId && item.status !== 'PROCESSING')) {
      pending.value = undefined;
      text.value = ''
    }
  } catch (e) {
    if (request === readRequest) error.value = explain(e)
  } finally {
    if (request === readRequest) busy.value = false
  }
}

async function create() {
  if (busy.value || pending.value) return
  busy.value = true;
  error.value = ''
  const body = {dataMode: DATA_MODE}
  try {
    const created = await api<Conversation>('/ai/conversations', 'POST', body, keys.key('AI_CREATE', body))
    keys.done('AI_CREATE', body);
    selected.value = created.conversationId;
    current.value = created;
    text.value = ''
    messages.value = [];
    result.value = {};
    chosen.value = undefined;
    booking.value = false
    sessions.value = [created, ...sessions.value.filter(item => item.conversationId !== created.conversationId)]
    await load();
    busy.value = true;
    await list()
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  busy.value = true
  try {
    await list();
    if (sessions.value.length) {
      selected.value = sessions.value[0]!.conversationId;
      await load()
    }
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
})

async function send() {
  if (busy.value || !current.value || current.value.status !== 'ACTIVE' || selected.value !== current.value.conversationId
      || pending.value && pending.value.conversationId !== selected.value) return
  if (!pending.value) {
    if (!text.value.trim()) {
      error.value = '请描述查询需求';
      return
    }
    pending.value = {
      conversationId: current.value.conversationId,
      body: {
        text: text.value.trim(),
        clientMessageId: crypto.randomUUID(),
        expectedConditionVersion: current.value.version
      }
    }
  }
  const request = pending.value
  busy.value = true;
  error.value = ''
  try {
    // AI 处理预算十秒，另留实时认证及两次短事务的余量；超时后仍使用原消息编号恢复。
    const accepted = await api<Result>('/ai/conversations/' + request.conversationId + '/messages', 'POST', request.body, undefined, 20000)
    if (current.value?.conversationId === request.conversationId && accepted.conditions && accepted.conditionVersion !== undefined) {
      current.value = {
        ...current.value,
        conditions: accepted.conditions,
        version: accepted.conditionVersion,
        processing: false
      }
    }
    pending.value = undefined;
    text.value = '';
    await load()
  } catch (e) {
    error.value = explain(e)
    // 确定性拒绝不重放旧条件；在途、限流、网络或服务故障需要保留原消息并读取处理结果。
    if (e instanceof ApiError && e.status >= 400 && e.status < 500 && ![409, 429].includes(e.status)) pending.value = undefined
    if (e instanceof ApiError && ['AI_MESSAGE_ID_REUSED', 'AI_CONVERSATION_CLOSED', 'AI_RATE_LIMITED', 'BUSINESS_RATE_LIMITED'].includes(e.code)) pending.value = undefined
    if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') {
      pending.value = undefined;
      await load();
      error.value = '会话条件已更新，请核对当前条件后重新发送'
    }
  } finally {
    busy.value = false
  }
}

async function close() {
  if (busy.value || !current.value || pending.value) return
  busy.value = true;
  error.value = ''
  try {
    await api('/ai/conversations/' + current.value.conversationId + '/close', 'POST', {expectedConditionVersion: current.value.version})
    selected.value = '';
    current.value = undefined;
    messages.value = [];
    result.value = {};
    await list()
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

function prepare(plan: Plan) {
  chosen.value = plan;
  activity.value = '';
  description.value = '';
  booking.value = true;
  error.value = ''
}

async function submit() {
  const plan = chosen.value
  if (busy.value || !plan) return
  if (!activity.value.trim()) {
    error.value = '请填写活动名称';
    return
  }
  if (description.value.trim().length > descriptionLimit) {
    error.value = '活动说明不能超过 ' + descriptionLimit + ' 字符';
    return
  }
  const body = {
    activityName: activity.value.trim(),
    description: description.value.trim() || null,
    participantCount: plan.participantCount,
    startAt: plan.startAt,
    endAt: plan.endAt,
    venueId: plan.venueId,
    equipmentIds: [...plan.equipmentIds],
    requiredFeatures: [...plan.requiredFeatures]
  }
  busy.value = true;
  error.value = ''
  try {
    await api<ApplicationDetail>('/applications', 'POST', body, keys.key('AI_APPLICATION', body))
    keys.done('AI_APPLICATION', body);
    booking.value = false;
    ElMessage.success('申请已提交，等待审批');
    await router.push('/applications')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

function ordinary() {
  const conditions = current.value?.conversationId === selected.value ? current.value.conditions : undefined
  // 普通查询页面接收可编辑条件，进入后仍需用户点击查询，不沿用旧方案作为实时可用性。
  router.push({path: '/availability', query: conditions ? {intent: JSON.stringify(conditions)} : {}})
}
</script>
<template>
  <div class="page-heading">
    <div><span class="eyebrow">说出想法，一起找到合适的空间</span>
      <h1>智能资源查询</h1>
      <p class="lead">描述人数、时间和设备需求，核对条件后选择方案。智能查询不会直接创建预约。</p></div>
    <span class="query-label"><AppIcon name="spark" :size="15"/>校园资源助手</span>
  </div>
  <p v-if="error&&!booking" role="alert" class="error">{{ error }}</p>
  <div class="card ai-workspace">
    <div class="toolbar ai-session-toolbar">
      <el-select v-model="selected" placeholder="选择本人会话" :disabled="busy||!!pending" @change="load">
        <el-option v-for="item in sessions" :key="item.conversationId" :value="item.conversationId"
                   :label="'会话 '+item.conversationId"/>
      </el-select>
      <el-button :disabled="busy||!!pending" @click="create">
        <AppIcon name="plus" :size="15"/>
        新建会话
      </el-button>
      <el-button text :disabled="busy||!selected" @click="load">读取会话结果</el-button>
      <el-button text :disabled="busy||!current||!!pending||current.processing" @click="close">关闭会话</el-button>
      <el-button text class="right" @click="ordinary">改用普通查询
        <AppIcon name="arrow" :size="15"/>
      </el-button>
    </div>
    <div v-if="!current" class="ai-welcome"><span class="ai-welcome-icon"><AppIcon name="spark" :size="30"/></span>
      <h2>为下一次活动，找一个好地方</h2>
      <p>先新建会话，或选择已有会话继续查询。</p>
      <div class="query-steps"><span>01 描述活动需求</span><span>02 核对查询条件</span><span>03 选择资源方案</span>
      </div>
    </div>
    <template v-else>
      <div v-if="!messages.length" class="ai-welcome ai-welcome-compact"><span class="ai-welcome-icon"><AppIcon
          name="spark" :size="28"/></span>
        <h2>想找什么样的校园资源？</h2>
        <p>告诉我活动时间、参与人数，以及需要的场地设施或设备。</p>
      </div>
      <div v-for="item in messages" :key="item.messageId" class="ai-message" data-testid="ai-message">
        <div class="message-row message-user"><span class="message-avatar">我</span>
          <div><strong>你的需求</strong>
            <p>{{ item.text }}</p></div>
        </div>
        <div class="message-row message-assistant"><span class="message-avatar"><AppIcon name="spark"
                                                                                         :size="17"/></span>
          <div><strong>查询回复</strong>
            <p>{{ item.reply }}</p></div>
        </div>
      </div>
      <el-form class="ai-composer" label-position="top" @submit.prevent="send">
        <el-form-item label="查询需求">
          <el-input v-model="text" type="textarea" :rows="3" maxlength="2000" show-word-limit
                    :disabled="busy||!!pending"
                    placeholder="例如：明天下午两点开始，六个人讨论两小时，需要投影"/>
        </el-form-item>
        <div class="composer-actions"><span>核对条件后，再提交预约申请</span>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="current.processing&&!pending">
            {{ pending ? '重试原消息' : '发送需求' }}
            <AppIcon name="arrow" :size="16"/>
          </el-button>
        </div>
      </el-form>
      <p v-if="pending" class="small">上次消息结果尚未确认，已保留原消息编号。可以重试原消息或读取会话结果。</p>
      <p v-if="current.processing" role="status">消息正在处理，可稍后读取会话结果。</p>
      <section class="ai-conditions">
        <h2>
          <AppIcon name="sliders" :size="17"/>
          当前条件（上海时间）
        </h2>
        <p data-testid="ai-conditions">
          日期：{{ current.conditions.date || '待确认' }}；开始：{{ current.conditions.startLocal || '待确认' }}；
          结束：{{ current.conditions.endLocal || (current.conditions.durationMinutes ? current.conditions.durationMinutes + ' 分钟' : '待确认') }}；人数：{{ current.conditions.participantCount || '待确认' }}</p>
        <p>校区：{{ current.conditions.campus || '未限定' }}；楼栋：{{ current.conditions.preferredBuilding || '未限定' }}；
          设施：{{ current.conditions.requiredFeatures?.map(f => labels[f] || f).join('、') || '无额外要求' }}</p>
        <p>
          设备：{{ current.conditions.equipmentRequirements?.map(n => n.type + ' × ' + n.count).join('、') || '无额外要求' }}</p>
      </section>
      <el-alert v-if="result.status==='FAILED'" type="warning" :closable="false"
                :title="result.reply||'智能查询暂不可用，可改用普通查询。'"/>
      <div v-for="plan in result.plans||[]" :key="plan.venueId" class="ai-plan" data-testid="ai-plan">
        <span class="plan-kicker"><AppIcon name="campus" :size="17"/>资源方案</span>
        <h2>{{ plan.venueName }}</h2>
        <p>{{ display(plan.startAt) }} 至 {{ display(plan.endAt) }} · {{ plan.participantCount }} 人</p>
        <p>
          设备：{{ plan.resources.filter(r => r.resourceType === 'EQUIPMENT').map(r => r.name).join('、') || '无需单件设备' }}</p>
        <p class="small">查询时已确认完整覆盖 · 核查时间 {{ display(plan.checkedAt) }} · 规则版本
          {{ plan.policyVersion }}</p>
        <p class="small">规则依据：{{ plan.ruleBasis }}</p>
        <el-button type="primary" :disabled="busy||!!pending" @click="prepare(plan)">核对并申请此方案</el-button>
      </div>
    </template>
  </div>
  <el-dialog v-model="booking" title="确认活动申请" width="600px" :close-on-click-modal="false">
    <p v-if="chosen">{{ chosen.venueName }} · {{ display(chosen.startAt) }} 至 {{ display(chosen.endAt) }} ·
      {{ chosen.participantCount }} 人</p>
    <p>提交会重新检查整个场地与设备组合；方案生成不代表预约成功，之后仍需审批。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
      <el-form-item label="活动名称">
        <el-input v-model="activity" maxlength="200"/>
      </el-form-item>
      <el-form-item label="活动说明">
        <el-input v-model="description" type="textarea" :maxlength="descriptionLimit" show-word-limit/>
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy">确认提交申请</el-button>
    </el-form>
  </el-dialog>
</template>
