<script setup lang="ts">
/**
 * 根据时间、人数、设施及设备需求查询可行组合，并查看资源日历。
 * 结果是服务端检查时刻的快照，页面展示不会锁定资源或创建预约申请。
 */
import {ref, onMounted} from 'vue'
import {api, ApiError, explain, mutationKeys} from '../api/client'
import {DATA_MODE} from '../api/dataMode'
import {useSession} from '../stores/session'
import {useRouter, useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {usePagedOptions} from '../composables/usePagedOptions'
import ShanghaiDateTime from '../components/ShanghaiDateTime.vue'
import {shanghaiRange} from '../utils/time'
import type {Resource, Page as ResourcePage, ApplicationDetail} from '../types'

interface Plan {
  venueId: string;
  venueName: string;
  equipmentIds: string[];
  approvalGroupId: string;
  dataMode: string;
  policyVersion: number;
  checkedAt: string
}

interface SearchResult {
  items: Plan[];
  total: number;
  unavailableReason?: string
}

interface Calendar {
  coverageStatus: string;
  checkedAt: string;
  intervals: { startAt: string; endAt: string; type: string }[]
  coverageIntervals: { from: string; to: string; coverageStatus: string }[]
  openingRules: {
    weekday: number;
    startLocal: string;
    endLocal: string;
    validFrom: string;
    validTo: string;
    enabled: boolean
  }[]
}

const start = ref(''), end = ref(''), campus = ref(''), building = ref(''), participants = ref(20)
const features = ref<string[]>([]), equipment = ref<{ type: string; count: number }[]>([])
const result = ref<SearchResult>(), calendar = ref<Calendar>(), resourceId = ref('')
const busy = ref(false), error = ref('')
const session = useSession(), router = useRouter(), keys = mutationKeys()
// 保存查询成功时的条件，申请使用这份快照，不读取后来被编辑的筛选字段。
const searched = ref<{ startAt: string; endAt: string; participantCount: number; requiredFeatures: string[] }>()
const canWaitlist = ref(false)
const bookingDialog = ref(false), bookingPlan = ref<Plan>(), activityName = ref(''), description = ref('')
const labels: Record<string, string> = {COMPLETE: '已确认完整覆盖', PARTIAL: '仅部分覆盖', UNKNOWN: '覆盖待核实'}
const occupancyLabels: Record<string, string> = {
  EXTERNAL: '外部占用',
  HOLD: '待审批暂占',
  WAITLIST_HOLD: '候补待确认暂占',
  CONFIRMED: '已批准预约',
  CONFLICT_REVIEW: '冲突待复核占用'
}
const featureOptions = [{code: 'PROJECTOR', label: '投影'}, {code: 'WHITEBOARD', label: '白板'}, {
  code: 'SOUND_SYSTEM',
  label: '音响'
}, {code: 'NETWORK', label: '网络'}, {code: 'COMPUTER', label: '电脑'}, {
  code: 'MICROPHONE',
  label: '固定话筒'
}, {code: 'DESK', label: '桌椅'}]

// 组合查询与日历共用上海时刻输入；组合查询另要求同一自然日。
function range(singleDay = false) {
  return shanghaiRange(start.value, end.value, singleDay)
}

const {items: resources, loading: resourceLoading, hasMore: resourceHasMore, more: loadNextResourcePage} =
    usePagedOptions<Resource>(page => api<ResourcePage<Resource>>('/resources?type=VENUE&dataMode=' + DATA_MODE + '&size=100&page=' + page))

async function loadResources() {
  try {
    await loadNextResourcePage()
  } catch (e) {
    error.value = explain(e)
  }
}

async function more() {
  error.value = ''
  await loadResources()
}

onMounted(() => {
  // AI 降级只预填已确认条件，不自动查询或提交；URL 条件仍经本页与后端重复校验。
  const route = useRoute()
  if (typeof route.query.intent === 'string' && route.query.intent.length < 10000) {
    try {
      const intent = JSON.parse(route.query.intent)
      const wall = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))
          ? new Date(Date.parse(value) + 8 * 3600000).toISOString().slice(0, 19).replace('T', ' ') : ''
      start.value = wall(intent.startAt);
      end.value = wall(intent.endAt)
      if (typeof intent.participantCount === 'number' && Number.isInteger(intent.participantCount) && intent.participantCount > 0 && intent.participantCount <= 100000) participants.value = intent.participantCount
      if (typeof intent.campus === 'string') campus.value = intent.campus
      if (typeof intent.preferredBuilding === 'string') building.value = intent.preferredBuilding
      if (Array.isArray(intent.requiredFeatures) && intent.requiredFeatures.every((f: unknown) => typeof f === 'string' && featureOptions.some(o => o.code === f))) features.value = intent.requiredFeatures
      if (Array.isArray(intent.equipmentRequirements) && intent.equipmentRequirements.length <= 10 && intent.equipmentRequirements.every((n: {
        type?: unknown;
        count?: unknown
      }) => n && typeof n.type === 'string' && typeof n.count === 'number' && Number.isInteger(n.count) && n.count >= 1 && n.count <= 20)) equipment.value = intent.equipmentRequirements
    } catch {
      error.value = '预填条件格式无效，请手动核对'
    }
  }
  loadResources()
})

// 清除旧结果再查询，避免失败后仍把上一次方案当作本次结果。当前页面最多展示返回的前 100 套组合。
async function search() {
  if (busy.value) return
  busy.value = true;
  error.value = '';
  result.value = undefined
  searched.value = undefined
  try {
    const intent = {...range(true), participantCount: participants.value, requiredFeatures: [...features.value]}
    result.value = await api<SearchResult>('/availability/search', 'POST', {
      ...intent,
      campus: campus.value || null,
      preferredBuilding: building.value || null,
      participantCount: participants.value,
      requiredFeatures: features.value,
      equipmentRequirements: equipment.value,
      dataMode: DATA_MODE,
      page: 1,
      size: 100
    })
    searched.value = intent
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

function prepareBooking(plan: Plan) {
  if (busy.value || !session.applicant || !searched.value) return
  canWaitlist.value = false;
  bookingPlan.value = plan;
  activityName.value = '';
  description.value = '';
  error.value = '';
  bookingDialog.value = true
}

async function submitBooking(waitlist = false) {
  const plan = bookingPlan.value, intent = searched.value
  if (busy.value || !plan || !intent) return
  if (!activityName.value.trim()) {
    error.value = '请填写活动名称';
    return
  }
  const payload = {
    ...intent, activityName: activityName.value.trim(), description: description.value.trim() || null,
    venueId: plan.venueId, equipmentIds: [...plan.equipmentIds]
  }
  const scope = waitlist ? 'WAITLIST_CREATE' : 'APPLICATION_CREATE'
  busy.value = true;
  error.value = ''
  try {
    await api<ApplicationDetail>(waitlist ? '/waitlists' : '/applications', 'POST', payload, keys.key(scope, payload))
    keys.done(scope, payload);
    bookingDialog.value = false
    ElMessage.success(waitlist ? '已加入候补，等待整组资源可用' : '申请已提交，等待审批；可在我的申请查看当前状态')
    await router.push(waitlist ? '/waitlists' : '/applications')
  } catch (e) {
    error.value = explain(e);
    if (e instanceof ApiError && ['RESOURCE_CONFLICT', 'WAITLIST_PRIORITY'].includes(e.code)) canWaitlist.value = true
  } finally {
    busy.value = false
  }
}

// 使用同一查询窗口读取指定资源的日历，切换资源时清除旧日历，避免混淆覆盖证据。
async function showCalendar(id = resourceId.value) {
  if (busy.value) return
  busy.value = true;
  error.value = '';
  calendar.value = undefined;
  resourceId.value = id
  try {
    if (!id) throw new Error('请选择场地');
    const dates = range();
    calendar.value = await api<Calendar>('/resources/' + id + '/calendar?' + new URLSearchParams({
      from: dates.startAt,
      to: dates.endAt
    }))
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 展示固定采用上海时区，与后端自然日规则一致，不随浏览器所在时区改变业务日期。
function display(value: string) {
  return new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'})
}
</script>
<template>
  <div class="page-heading">
    <div><span class="eyebrow">为下一次活动安排好时间</span>
      <h1>可用时段查询</h1>
      <p class="lead">查找一处场地和同审批组设备。只有来源覆盖有效、开放且无已知冲突的组合才会展示。</p></div>
    <router-link to="/resources" class="text-action">浏览资源目录<span aria-hidden="true">→</span></router-link>
  </div>
  <el-alert title="查询结果不会预留资源。提交申请后才暂占整组资源，审批通过后方可使用。" type="info"
            :closable="false"/>
  <div class="card availability-search">
    <div class="form-section-heading"><span class="section-number">01</span>
      <div><h2>设置查询条件</h2>
        <p>选择活动时间，补充人数与设备需求</p></div>
    </div>
    <el-form label-position="top" :disabled="busy" @submit.prevent="search">
      <p class="small">输入和结果均按上海时间（UTC+08:00）处理，组合查询需在同一自然日内。</p>
      <div class="twocol">
        <el-form-item label="开始时间">
          <ShanghaiDateTime v-model="start" data-testid="availability-start"/>
        </el-form-item>
        <el-form-item label="结束时间">
          <ShanghaiDateTime v-model="end" data-testid="availability-end"/>
        </el-form-item>
        <el-form-item label="校区">
          <el-input v-model="campus" maxlength="100"/>
        </el-form-item>
        <el-form-item label="楼栋">
          <el-input v-model="building" maxlength="100"/>
        </el-form-item>
        <el-form-item label="活动人数">
          <el-input-number v-model="participants" :min="1" :max="100000" :precision="0"/>
        </el-form-item>
      </div>
      <el-form-item label="场地配置">
        <el-checkbox-group v-model="features">
          <el-checkbox v-for="f in featureOptions" :key="f.code" :value="f.code">{{ f.label }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <div v-for="(need,index) in equipment" :key="index" class="twocol">
        <el-form-item label="设备类型">
          <el-select v-model="need.type" filterable allow-create default-first-option>
            <el-option label="话筒" value="MICROPHONE"/>
            <el-option label="投影设备" value="PROJECTOR"/>
            <el-option label="电脑" value="COMPUTER"/>
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <div class="input-action">
            <el-input-number v-model="need.count" :min="1" :max="20" :precision="0"/>
            <el-button text @click="equipment.splice(index,1)">移除</el-button>
          </div>
        </el-form-item>
      </div>
      <div class="form-actions">
        <el-button :disabled="equipment.length>=10" @click="equipment.push({type:'MICROPHONE',count:1})">添加设备需求
        </el-button>
        <el-button native-type="submit" type="primary" :loading="busy">查询可用组合</el-button>
      </div>
    </el-form>
  </div>
  <p v-if="error && !bookingDialog" class="error" role="alert">{{ error }}</p>
  <div v-if="result" class="card" data-testid="availability-results">
    <h2>查询结果 · {{ result.total }} 处场地</h2>
    <p v-if="result.unavailableReason==='POLICY_NOT_CONFIGURED'">尚未配置已确认的预约策略，请联系管理员。</p>
    <p v-else-if="!result.items.length">没有满足全部条件的组合。可调整时段、人数或设备需求，并查看日历确认未知范围。</p>
    <p v-if="result.total>result.items.length" class="small">当前显示前 {{ result.items.length }} 项，请缩小筛选范围。</p>
    <el-table :data="result.items">
      <el-table-column prop="venueName" label="场地" min-width="180"/>
      <el-table-column label="设备编号" min-width="160">
        <template #default="{row}">{{ row.equipmentIds.join('、') || '无需设备' }}</template>
      </el-table-column>
      <el-table-column prop="policyVersion" label="规则版本" width="100"/>
      <el-table-column label="操作" width="220">
        <template #default="{row}">
          <el-button text :disabled="busy" @click="showCalendar(row.venueId)">查看日历</el-button>
          <el-button v-if="session.applicant" text type="primary" :disabled="busy" @click="prepareBooking(row)">
            申请此组合
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <div class="card availability-calendar">
    <div class="form-section-heading"><span class="section-number">02</span>
      <div><h2>场地日历（上海时间）</h2>
        <p>查看单处场地的开放规则、占用与来源覆盖</p></div>
    </div>
    <div class="calendar-toolbar">
      <el-select v-model="resourceId" filterable placeholder="选择可见场地">
        <el-option v-for="r in resources" :key="r.id" :label="r.name" :value="r.id"/>
      </el-select>
      <el-button v-if="resourceHasMore" :loading="resourceLoading" @click="more">加载更多场地</el-button>
      <el-button :loading="busy" @click="showCalendar()">查看选定时段</el-button>
    </div>
    <div v-if="calendar" data-testid="availability-calendar"><p>
      {{ labels[calendar.coverageStatus] || calendar.coverageStatus }} · 查询时间 {{ display(calendar.checkedAt) }}</p>
      <h3>来源覆盖区间</h3>
      <p v-if="!calendar.coverageIntervals.length">当前时段没有有效覆盖证明，空白范围不能视为空闲。</p>
      <el-table :data="calendar.coverageIntervals">
        <el-table-column label="开始" min-width="165">
          <template #default="{row}">{{ display(row.from) }}</template>
        </el-table-column>
        <el-table-column label="结束" min-width="165">
          <template #default="{row}">{{ display(row.to) }}</template>
        </el-table-column>
        <el-table-column label="覆盖状态" min-width="110">
          <template #default="{row}">{{ labels[row.coverageStatus] || row.coverageStatus }}</template>
        </el-table-column>
      </el-table>
      <h3>开放规则</h3>
      <p v-if="!calendar.openingRules.length">尚未配置开放规则。</p>
      <el-table :data="calendar.openingRules">
        <el-table-column label="星期" min-width="80">
          <template #default="{row}">星期{{ ['一', '二', '三', '四', '五', '六', '日'][row.weekday - 1] }}</template>
        </el-table-column>
        <el-table-column prop="startLocal" label="开始时刻" min-width="110"/>
        <el-table-column prop="endLocal" label="结束时刻" min-width="110"/>
        <el-table-column prop="validFrom" label="生效日期" min-width="120"/>
        <el-table-column prop="validTo" label="结束日期" min-width="120"/>
        <el-table-column label="状态" min-width="90">
          <template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template>
        </el-table-column>
      </el-table>
      <h3>占用时段</h3>
      <p v-if="!calendar.intervals.length">未发现有效占用；仍需结合覆盖状态和开放规则判断。</p>
      <el-table :data="calendar.intervals">
        <el-table-column label="开始" min-width="165">
          <template #default="{row}">{{ display(row.startAt) }}</template>
        </el-table-column>
        <el-table-column label="结束" min-width="165">
          <template #default="{row}">{{ display(row.endAt) }}</template>
        </el-table-column>
        <el-table-column label="类别" min-width="160">
          <template #default="{row}">{{ occupancyLabels[row.type] || '占用' }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
  <el-dialog v-model="bookingDialog" title="提交联合预约" width="600px" :close-on-click-modal="!busy"
             :close-on-press-escape="!busy" :show-close="!busy">
    <div v-if="bookingPlan && searched" data-testid="booking-form">
      <p>{{ bookingPlan.venueName }} · {{ searched.participantCount }} 人</p>
      <p>{{ display(searched.startAt) }} 至 {{ display(searched.endAt) }}（上海时间）</p>
      <p>设备编号：{{ bookingPlan.equipmentIds.join('、') || '无需设备' }}</p>
      <el-form label-position="top" :disabled="busy" @submit.prevent="submitBooking(false)">
        <el-form-item label="活动名称">
          <el-input v-model="activityName" maxlength="200" data-testid="booking-name"/>
        </el-form-item>
        <el-form-item label="活动说明">
          <el-input v-model="description" type="textarea" maxlength="2000" :rows="4" data-testid="booking-description"/>
        </el-form-item>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <el-button native-type="submit" type="primary" :loading="busy">确认提交申请</el-button>
        <el-button v-if="canWaitlist" :disabled="busy" @click="submitBooking(true)">按此组合加入候补</el-button>
      </el-form>
    </div>
  </el-dialog>
</template>
