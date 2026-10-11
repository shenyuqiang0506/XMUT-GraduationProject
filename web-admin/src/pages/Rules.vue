<script setup lang="ts">
import {ref, reactive, onMounted} from 'vue'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import {useSession} from '../stores/session'
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

const session = useSession(), keys = mutationKeys(), resources = ref<Resource[]>([]), resourceId = ref(''),
    detail = ref<Detail>()
const resourcePage = ref(1), resourceTotal = ref(0), rules = ref<OpeningRule[]>([]), occupancies = ref<Occupancy[]>([]),
    error = ref(''), busy = ref(false)
const source = ref('MANUAL'), sourceKey = ref<string>(crypto.randomUUID()), start = ref<Date>(), end = ref<Date>(),
    reason = ref('MAINTENANCE')
const occupancyPage = ref(1), occupancyTotal = ref(0), policyLoading = ref(false)
const scope = ref('DEMO'), policyVersion = ref(0), basis = ref(''),
    settings = reactive<Record<string, number | undefined>>({})
const fields = [
  {key: 'pendingApprovalTtlMinutes', label: '待审批暂占（分钟）', min: 1, max: 10080, demo: 60},
  {key: 'waitlistOfferTtlMinutes', label: '候补确认时限（分钟）', min: 1, max: 1440, demo: 15},
  {key: 'bookingLeadMinutes', label: '最晚提前申请（分钟）', min: 0, max: 43200, demo: 0},
  {key: 'cancelLeadMinutes', label: '取消提前量（分钟）', min: 0, max: 43200, demo: 30},
  {key: 'maxBookingDaysAhead', label: '最远预约天数', min: 1, max: 365, demo: 30},
  {key: 'maxDurationMinutes', label: '活动最大时长（分钟）', min: 1, max: 1440, demo: 240},
  {key: 'maxActiveApplicationsPerUser', label: '同时有效申请上限', min: 1, max: 100, demo: 3},
  {key: 'coverageFreshnessMinutes', label: '来源覆盖有效时长（分钟）', min: 1, max: 10080, demo: 120}
]

async function loadResources() {
  try {
    const data = await api<Page<Resource>>('/resources?size=100&page=' + resourcePage.value);
    resources.value.push(...data.items);
    resourceTotal.value = data.total
  } catch (e) {
    error.value = explain(e)
  }
}

async function more() {
  resourcePage.value++;
  await loadResources()
}

async function loadResource() {
  error.value = '';
  detail.value = undefined;
  rules.value = [];
  occupancies.value = [];
  occupancyPage.value = 1
  try {
    if (!resourceId.value) return;
    detail.value = await api<Detail>('/resources/' + resourceId.value);
    rules.value = detail.value.openingRules.map(r => ({...r}));
    await loadOccupancies()
  } catch (e) {
    error.value = explain(e)
  }
}

async function loadOccupancies() {
  const data = await api<Page<Occupancy>>('/admin/resources/' + resourceId.value + '/external-occupancies?size=100&page=' + occupancyPage.value)
  occupancies.value.push(...data.items)
  occupancyTotal.value = data.total
}

async function moreOccupancies() {
  busy.value = true
  try { occupancyPage.value++; await loadOccupancies() }
  catch (e) { occupancyPage.value--; error.value = explain(e) }
  finally { busy.value = false }
}

async function loadPolicy() {
  error.value = ''
  policyLoading.value = true
  const requestedScope = scope.value
  // 切换范围先清除上一套参数；迟到响应不能覆盖当前环境的规则。
  policyVersion.value = 0
  basis.value = ''
  fields.forEach(f => settings[f.key] = undefined)
  try {
    const p = await api<Policy>('/admin/policies/' + requestedScope)
    if (scope.value !== requestedScope) return
    policyVersion.value = p.version
    basis.value = p.basis || ''
    // 演示草案也需要显式保存；真实规则无默认值，不能把演示参数直接当成学校制度。
    fields.forEach(f => settings[f.key] = p.settings?.[f.key] ?? (requestedScope === 'DEMO' ? f.demo : undefined))
  } catch (e) {
    if (scope.value === requestedScope) error.value = explain(e)
  } finally {
    if (scope.value === requestedScope) policyLoading.value = false
  }
}

onMounted(async () => {
  await loadResources();
  await loadPolicy()
})

async function saveRules() {
  if (!detail.value) return;
  busy.value = true;
  error.value = ''
  const payload = {expectedVersion: detail.value.version, rules: rules.value}
  try {
    await api('/admin/resources/' + detail.value.id + '/opening-rules', 'PUT', payload, keys.key('opening:' + detail.value.id, payload));
    keys.done('opening:' + detail.value.id, payload);
    await loadResource();
    ElMessage.success('开放规则已保存')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function addOccupancy() {
  busy.value = true;
  error.value = ''
  try {
    if (!resourceId.value || !start.value || !end.value) throw new Error('请选择资源和起止时间');
    const payload = {
      sourceSystem: source.value,
      sourceKey: sourceKey.value,
      startAt: start.value.toISOString(),
      endAt: end.value.toISOString(),
      reasonCode: reason.value
    }
    await api('/admin/resources/' + resourceId.value + '/external-occupancies', 'POST', payload, keys.key('occupancy:' + resourceId.value, payload));
    keys.done('occupancy:' + resourceId.value, payload);
    sourceKey.value = crypto.randomUUID();
    await loadResource();
    ElMessage.success('外部占用已登记')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function disable(row: Occupancy) {
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

async function savePolicy() {
  busy.value = true;
  error.value = ''
  try {
    if (!basis.value.trim() || fields.some(f => settings[f.key] === undefined)) throw new Error('请填写全部参数和规则依据')
    const payload = {expectedVersion: policyVersion.value, settings: {...settings}, basis: basis.value}
    await api('/admin/policies/' + scope.value + '/versions', 'POST', payload, keys.key('policy:' + scope.value, payload));
    keys.done('policy:' + scope.value, payload);
    await loadPolicy();
    ElMessage.success('新策略版本已启用')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

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
        <el-button v-if="resources.length<resourceTotal" @click="more">加载更多资源</el-button>
      </el-form-item>
    </el-form>
  </div>
  <div v-if="detail" class="card">
    <h2>开放规则 · {{ detail.name }} · 版本 {{ detail.version }}</h2>
    <el-alert title="时刻按上海时间解释；删除全部规则会使该资源没有开放时段。" type="info" :closable="false"/>
    <div v-for="(rule,index) in rules" :key="index">
      <el-form label-position="top">
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
    <el-button :disabled="rules.length>=100" @click="addRule">新增开放规则</el-button>
    <el-button type="primary" :loading="busy" @click="saveRules">保存开放规则</el-button>
  </div>
  <div v-if="detail" class="card"><h2>外部占用（上海时间）</h2>
    <el-form label-position="top" @submit.prevent="addOccupancy">
      <div class="twocol">
        <el-form-item label="占用来源">
          <el-input v-model="source" maxlength="32"/>
        </el-form-item>
        <el-form-item label="来源占用编号">
          <el-input v-model="sourceKey" maxlength="128"/>
        </el-form-item>
        <el-form-item label="占用开始">
          <el-date-picker v-model="start" type="datetime"/>
        </el-form-item>
        <el-form-item label="占用结束">
          <el-date-picker v-model="end" type="datetime"/>
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
