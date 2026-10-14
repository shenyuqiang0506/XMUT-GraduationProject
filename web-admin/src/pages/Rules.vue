<script setup lang="ts">
/**
 * 授权管理员维护开放规则、外部占用及预约策略的页面。
 * 规则与占用沿用资源版本，策略按 REAL/DEMO 独立版本；真实规则不预填制度参数。
 */
import {ref, reactive, onMounted, onBeforeUnmount} from 'vue'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import {useSession} from '../stores/session'
import {usePagedOptions} from '../composables/usePagedOptions'
import ShanghaiDateTime from '../components/ShanghaiDateTime.vue'
import {shanghaiRange} from '../utils/time'
import type {Resource, Page} from '../types'

interface OpeningRule {
  weekday: number;
  startLocal: string;
  endLocal: string;
  validFrom: string;
  validTo: string;
  enabled: boolean
}

interface Detail extends Resource {
  openingRules: OpeningRule[]
}

interface Occupancy {
  id: string;
  sourceSystem: string;
  sourceKey: string;
  startAt: string;
  endAt: string;
  reasonCode: string;
  active: boolean;
  version: number
}

interface Policy {
  configured: boolean;
  version: number;
  settings?: Record<string, number>;
  basis?: string
}

const session = useSession(), keys = mutationKeys(), resourceId = ref(''),
    detail = ref<Detail>()
const rules = ref<OpeningRule[]>([]), occupancies = ref<Occupancy[]>([]),
    error = ref(''), busy = ref(false)
const source = ref('MANUAL'), sourceKey = ref<string>(crypto.randomUUID()), start = ref(''), end = ref(''),
    reason = ref('MAINTENANCE')
const occupancyPage = ref(1), occupancyTotal = ref(0), policyLoading = ref(false)
const detailLoading = ref(false)
let resourceRequest = 0, policyRequest = 0
onBeforeUnmount(() => { resourceRequest++; policyRequest++ })
// 日常业务默认使用真实来源；测试资料必须显式选择隔离模式。
const scope = ref('REAL'), policyVersion = ref(0), basis = ref(''),
    settings = reactive<Record<string, number | undefined>>({})
const fields = [
  {key: 'pendingApprovalTtlMinutes', label: '待审批暂占（分钟）', min: 1, max: 10080},
  {key: 'waitlistOfferTtlMinutes', label: '候补确认时限（分钟）', min: 1, max: 1440},
  {key: 'bookingLeadMinutes', label: '最晚提前申请（分钟）', min: 0, max: 43200},
  {key: 'cancelLeadMinutes', label: '取消提前量（分钟）', min: 0, max: 43200},
  {key: 'maxBookingDaysAhead', label: '最远预约天数', min: 1, max: 365},
  {key: 'maxDurationMinutes', label: '活动最大时长（分钟）', min: 1, max: 1440},
  {key: 'maxActiveApplicationsPerUser', label: '同时有效申请上限', min: 1, max: 100},
  {key: 'coverageFreshnessMinutes', label: '来源覆盖有效时长（分钟）', min: 1, max: 10080}
]

const {items: resources, loading: resourceLoading, hasMore: resourceHasMore, more: loadNextResourcePage} =
    usePagedOptions<Resource>(page => api<Page<Resource>>('/resources?size=100&page=' + page))

async function loadResources() {
  try { await loadNextResourcePage() }
  catch (e) { error.value = explain(e) }
}

async function more() {
  error.value = ''
  await loadResources()
}

// 固定本次资源编号与请求代数；详情和首页占用一并就绪后才展示可写表单。
// 允许加载期间继续切换资源，但任何迟到响应都不能写回当前资源的页面。
async function loadResource() {
  const id = resourceId.value, request = ++resourceRequest
  const current = () => request === resourceRequest && id === resourceId.value
  detailLoading.value = !!id
  error.value = '';
  detail.value = undefined;
  rules.value = [];
  occupancies.value = [];
  occupancyPage.value = 1
  occupancyTotal.value = 0
  try {
    if (!id) return
    const [loaded, data] = await Promise.all([
      api<Detail>('/resources/' + id),
      api<Page<Occupancy>>('/admin/resources/' + id + '/external-occupancies?size=100&page=1')
    ])
    if (!current()) return
    if (loaded.id !== id) throw new Error('资源详情与当前选择不一致，请重新加载')
    detail.value = loaded
    rules.value = loaded.openingRules.map(r => ({...r}))
    occupancies.value = data.items
    occupancyTotal.value = data.total
  } catch (e) {
    if (current()) error.value = explain(e)
  } finally {
    if (current()) detailLoading.value = false
  }
}

function selectedDetail() {
  if (detailLoading.value || !detail.value || detail.value.id !== resourceId.value)
    throw new Error('请等待当前资源加载完成后再操作')
  return detail.value
}

async function moreOccupancies() {
  if (busy.value || detailLoading.value || !detail.value) return
  const id = selectedDetail().id, request = resourceRequest, next = occupancyPage.value + 1
  busy.value = true
  try {
    const data = await api<Page<Occupancy>>('/admin/resources/' + id + '/external-occupancies?size=100&page=' + next)
    if (request !== resourceRequest || id !== resourceId.value) return
    const seen = new Set(occupancies.value.map(row => row.id))
    occupancies.value.push(...data.items.filter(row => !seen.has(row.id)))
    occupancyPage.value = next
    occupancyTotal.value = data.total
  }
  catch (e) { if (request === resourceRequest) error.value = explain(e) }
  finally { busy.value = false }
}

// 切换策略范围先清空全部参数；只接受仍属于当前范围的响应，防止异步返回顺序覆盖新选择。
async function loadPolicy() {
  const request = ++policyRequest
  error.value = ''
  policyLoading.value = true
  const requestedScope = scope.value
  // 切换范围先清除上一套参数；迟到响应不能覆盖当前环境的规则。
  policyVersion.value = 0
  basis.value = ''
  fields.forEach(f => settings[f.key] = undefined)
  try {
    const p = await api<Policy>('/admin/policies/' + requestedScope)
    if (request !== policyRequest || scope.value !== requestedScope) return
    policyVersion.value = p.version
    basis.value = p.basis || ''
    // 参数只能来自已保存的制度配置；未配置时留空，避免把示例数值当成真实规则。
    fields.forEach(f => settings[f.key] = p.settings?.[f.key])
  } catch (e) {
    if (request === policyRequest) error.value = explain(e)
  } finally {
    if (request === policyRequest) policyLoading.value = false
  }
}

onMounted(async () => {
  await loadResources();
  await loadPolicy()
})

// 一次替换整套规则并带资源版本，成功后重新读取服务器递增后的版本。
async function saveRules() {
  if (busy.value || detailLoading.value) return
  busy.value = true;
  error.value = ''
  try {
    const selected = selectedDetail(), id = selected.id
    const payload = {expectedVersion: selected.version, rules: rules.value.map(rule => ({...rule}))}
    await api('/admin/resources/' + id + '/opening-rules', 'PUT', payload, keys.key('opening:' + id, payload));
    keys.done('opening:' + id, payload);
    await loadResource();
    ElMessage.success('开放规则已保存')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 只在登记成功后生成新的来源编号；失败重试沿用编号和幂等键，避免重复登记同一占用。
async function addOccupancy() {
  if (busy.value || detailLoading.value) return
  busy.value = true;
  error.value = ''
  try {
    const id = selectedDetail().id
    if (!start.value || !end.value) throw new Error('请选择资源和起止时间');
    const payload = {
      sourceSystem: source.value,
      sourceKey: sourceKey.value,
      ...shanghaiRange(start.value, end.value),
      reasonCode: reason.value
    }
    await api('/admin/resources/' + id + '/external-occupancies', 'POST', payload, keys.key('occupancy:' + id, payload));
    keys.done('occupancy:' + id, payload);
    sourceKey.value = crypto.randomUUID();
    await loadResource();
    ElMessage.success('外部占用已登记')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 通过 active=false 停用记录，保留原区间、原因和版本供服务器校验，不物理删除历史。
async function disable(row: Occupancy) {
  if (busy.value || detailLoading.value) return
  busy.value = true;
  error.value = ''
  const payload = {
    expectedVersion: row.version,
    active: false,
    startAt: row.startAt,
    endAt: row.endAt,
    reasonCode: row.reasonCode
  }
  try {
    selectedDetail()
    if (!occupancies.value.some(item => item.id === row.id)) throw new Error('占用记录已变化，请重新加载')
    await api('/admin/external-occupancies/' + row.id, 'PATCH', payload, keys.key('disable:' + row.id, payload));
    keys.done('disable:' + row.id, payload);
    await loadResource();
    ElMessage.success('占用已停用')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 全部参数和制度依据均需明确填写，创建新版本后重新加载生效策略。
async function savePolicy() {
  if (busy.value || policyLoading.value) return
  const selectedScope = scope.value
  busy.value = true;
  error.value = ''
  try {
    if (!basis.value.trim() || fields.some(f => settings[f.key] === undefined)) throw new Error('请填写全部参数和规则依据')
    const payload = {expectedVersion: policyVersion.value, settings: {...settings}, basis: basis.value}
    await api('/admin/policies/' + selectedScope + '/versions', 'POST', payload, keys.key('policy:' + selectedScope, payload));
    keys.done('policy:' + selectedScope, payload);
    await loadPolicy();
    ElMessage.success('新策略版本已启用')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 这里只生成待编辑行，日期仍为空；管理员核实并保存前不会形成实际开放规则。
function addRule() {
  rules.value.push({
    weekday: 1,
    startLocal: '08:00:00',
    endLocal: '18:00:00',
    validFrom: '',
    validTo: '',
    enabled: true
  })
}

function display(value: string) {
  return new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'})
}
</script>
<template>
  <h1>开放与占用管理</h1>
  <p class="lead">按授权范围维护开放时段、来源占用和规则版本。登记占用不会自动产生完整覆盖证明。</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="card">
    <el-form label-position="top">
      <el-form-item label="选择资源">
        <el-select v-model="resourceId" :disabled="busy" filterable @change="loadResource">
          <el-option v-for="r in resources" :key="r.id" :label="r.name+' · '+r.dataMode" :value="r.id"/>
        </el-select>
        <el-button v-if="resourceHasMore" :loading="resourceLoading" :disabled="busy" @click="more">加载更多资源</el-button>
      </el-form-item>
    </el-form>
  </div>
  <p v-if="detailLoading" role="status">正在加载所选资源的规则和占用…</p>
  <div v-if="detail" class="card">
    <h2>开放规则 · {{ detail.name }} · 版本 {{ detail.version }}</h2>
    <el-alert title="时刻按上海时间解释；删除全部规则会使该资源没有开放时段。" type="info" :closable="false"/>
    <div v-for="(rule,index) in rules" :key="index">
      <el-form label-position="top" :disabled="busy||detailLoading">
        <div class="twocol">
          <el-form-item label="星期">
            <el-select v-model="rule.weekday">
              <el-option v-for="(name,i) in ['一','二','三','四','五','六','日']" :key="i" :label="'星期'+name"
                         :value="i+1"/>
            </el-select>
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="rule.enabled"/>
          </el-form-item>
          <el-form-item label="开放开始">
            <el-time-picker v-model="rule.startLocal" value-format="HH:mm:ss"/>
          </el-form-item>
          <el-form-item label="开放结束">
            <el-time-picker v-model="rule.endLocal" value-format="HH:mm:ss"/>
          </el-form-item>
          <el-form-item label="有效开始日期">
            <el-date-picker v-model="rule.validFrom" value-format="YYYY-MM-DD"/>
          </el-form-item>
          <el-form-item label="有效结束日期">
            <el-date-picker v-model="rule.validTo" value-format="YYYY-MM-DD"/>
          </el-form-item>
        </div>
        <el-button text @click="rules.splice(index,1)">删除此规则</el-button>
      </el-form>
    </div>
    <el-button :disabled="busy||detailLoading||rules.length>=100" @click="addRule">新增开放规则</el-button>
    <el-button type="primary" :loading="busy" :disabled="detailLoading" @click="saveRules">保存开放规则</el-button>
  </div>
  <div v-if="detail" class="card"><h2>外部占用（上海时间）</h2>
    <el-form label-position="top" :disabled="busy" @submit.prevent="addOccupancy">
      <p class="small">占用起止时刻按上海时间（UTC+08:00）填写。</p>
      <div class="twocol">
        <el-form-item label="占用来源">
          <el-input v-model="source" maxlength="32"/>
        </el-form-item>
        <el-form-item label="来源占用编号">
          <el-input v-model="sourceKey" maxlength="128"/>
        </el-form-item>
        <el-form-item label="占用开始">
          <ShanghaiDateTime v-model="start"/>
        </el-form-item>
        <el-form-item label="占用结束">
          <ShanghaiDateTime v-model="end"/>
        </el-form-item>
        <el-form-item label="原因">
          <el-select v-model="reason">
            <el-option label="课程" value="COURSE"/>
            <el-option label="考试" value="EXAM"/>
            <el-option label="维护" value="MAINTENANCE"/>
            <el-option label="其他" value="OTHER"/>
          </el-select>
        </el-form-item>
      </div>
      <el-button native-type="submit" type="primary" :loading="busy">登记外部占用</el-button>
    </el-form>
    <el-table :data="occupancies">
      <el-table-column prop="sourceSystem" label="来源"/>
      <el-table-column prop="sourceKey" label="来源编号"/>
      <el-table-column label="开始">
        <template #default="{row}">{{ display(row.startAt) }}</template>
      </el-table-column>
      <el-table-column label="结束">
        <template #default="{row}">{{ display(row.endAt) }}</template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{row}">{{ row.active ? '有效' : '已停用' }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{row}">
          <el-button text :disabled="!row.active||busy" @click="disable(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="occupancies.length<occupancyTotal" :loading="busy" @click="moreOccupancies">加载更多占用记录</el-button>
  </div>
  <div class="card"><h2>预约策略 · 当前版本 {{ policyVersion }}</h2>
    <el-alert title="演示数值仅为草案，保存后才启用。真实制度需填写已确认参数及依据；历史版本会保留。" type="info"
              :closable="false"/>
    <el-form label-position="top" :disabled="busy||policyLoading" @submit.prevent="savePolicy">
      <el-form-item label="策略范围">
        <el-select v-model="scope" :disabled="busy||policyLoading" @change="loadPolicy">
          <el-option label="演示 DEMO" value="DEMO"/>
          <el-option label="真实 REAL" value="REAL"/>
        </el-select>
      </el-form-item>
      <div class="twocol">
        <el-form-item v-for="f in fields" :key="f.key" :label="f.label">
          <el-input-number v-model="settings[f.key]" :min="f.min" :max="f.max" :precision="0"
                           :disabled="!session.admin"/>
        </el-form-item>
      </div>
      <el-form-item label="规则依据">
        <el-input v-model="basis" type="textarea" maxlength="500" :disabled="!session.admin"/>
      </el-form-item>
      <el-button v-if="session.admin" native-type="submit" type="primary" :loading="busy">保存并启用新版本</el-button>
      <p v-else class="small">仅系统管理员可以修改策略。</p>
    </el-form>
  </div>
</template>
