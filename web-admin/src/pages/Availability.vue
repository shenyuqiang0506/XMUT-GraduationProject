<script setup lang="ts">
import {ref, onMounted} from 'vue'
import {api, explain} from '../api/client'
import type {Resource, Page as ResourcePage} from '../types'

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

const start = ref<Date>(), end = ref<Date>(), campus = ref(''), building = ref(''), participants = ref(20),
    mode = ref('DEMO')
const features = ref<string[]>([]), equipment = ref<{ type: string; count: number }[]>([])
const result = ref<SearchResult>(), calendar = ref<Calendar>(), resourceId = ref(''), resources = ref<Resource[]>([])
const resourcePage = ref(1), resourceTotal = ref(0), busy = ref(false), error = ref('')
const labels: Record<string, string> = {COMPLETE: '已确认完整覆盖', PARTIAL: '仅部分覆盖', UNKNOWN: '覆盖待核实'}
const featureOptions = [{code: 'PROJECTOR', label: '投影'}, {code: 'WHITEBOARD', label: '白板'}, {
  code: 'SOUND_SYSTEM',
  label: '音响'
}, {code: 'NETWORK', label: '网络'}, {code: 'COMPUTER', label: '电脑'}, {code: 'MICROPHONE', label: '固定话筒'}, {code: 'DESK', label: '桌椅'}]

function range() {
  if (!start.value || !end.value) throw new Error('请选择开始和结束时间')
  // 发送明确的 UTC 偏移；服务器按上海自然日判断是否跨日。
  return {startAt: start.value.toISOString(), endAt: end.value.toISOString()}
}

async function loadResources() {
  try {
    const data = await api<ResourcePage<Resource>>('/resources?type=VENUE&size=100&page=' + resourcePage.value);
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

onMounted(loadResources)

async function search() {
  busy.value = true;
  error.value = '';
  result.value = undefined
  try {
    result.value = await api<SearchResult>('/availability/search', 'POST', {
      ...range(),
      campus: campus.value || null,
      preferredBuilding: building.value || null,
      participantCount: participants.value,
      requiredFeatures: features.value,
      equipmentRequirements: equipment.value,
      dataMode: mode.value,
      page: 1,
      size: 100
    })
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function showCalendar(id = resourceId.value) {
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

function display(value: string) {
  return new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'})
}
</script>
<template>
  <h1>可用时段查询</h1>
  <p class="lead">查找一处场地和同审批组设备。只有来源覆盖有效、开放且无已知冲突的组合才会展示。</p>
  <el-alert title="查询不会预留资源。联合申请与审批仍在后续开发；演示资料不代表学校真实安排。" type="info"
            :closable="false"/>
  <div class="card">
    <el-form label-position="top" @submit.prevent="search">
      <div class="twocol">
        <el-form-item label="开始时间">
          <el-date-picker v-model="start" data-testid="availability-start" type="datetime"
                          format="YYYY-MM-DD HH:mm:ss"/>
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="end" data-testid="availability-end" type="datetime" format="YYYY-MM-DD HH:mm:ss"/>
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
        <el-form-item label="数据模式">
          <el-select v-model="mode">
            <el-option label="演示 DEMO" value="DEMO"/>
            <el-option label="真实 REAL" value="REAL"/>
          </el-select>
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
          <el-input-number v-model="need.count" :min="1" :max="20" :precision="0"/>
          <el-button text @click="equipment.splice(index,1)">移除</el-button>
        </el-form-item>
      </div>
      <el-button :disabled="equipment.length>=10" @click="equipment.push({type:'MICROPHONE',count:1})">添加设备需求
      </el-button>
      <el-button native-type="submit" type="primary" :loading="busy">查询可用组合</el-button>
    </el-form>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div v-if="result" class="card" data-testid="availability-results">
    <h2>查询结果 · {{ result.total }} 处场地</h2>
    <p v-if="result.unavailableReason==='POLICY_NOT_CONFIGURED'">尚未配置已确认的预约策略，请联系管理员。</p>
    <p v-else-if="!result.items.length">没有满足全部条件的组合。可调整时段、人数或设备需求，并查看日历确认未知范围。</p>
    <p v-if="result.total>result.items.length" class="small">当前显示前 {{ result.items.length }} 项，请缩小筛选范围。</p>
    <el-table :data="result.items">
      <el-table-column prop="venueName" label="场地"/>
      <el-table-column label="设备编号">
        <template #default="{row}">{{ row.equipmentIds.join('、') || '无需设备' }}</template>
      </el-table-column>
      <el-table-column prop="policyVersion" label="规则版本" width="100"/>
      <el-table-column label="操作" width="130">
        <template #default="{row}">
          <el-button text @click="showCalendar(row.venueId)">查看日历</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <div class="card"><h2>场地日历（上海时间）</h2>
    <el-select v-model="resourceId" filterable placeholder="选择可见场地">
      <el-option v-for="r in resources" :key="r.id" :label="r.name+' · '+r.dataMode" :value="r.id"/>
    </el-select>
    <el-button v-if="resources.length<resourceTotal" @click="more">加载更多场地</el-button>
    <el-button :loading="busy" @click="showCalendar()">查看选定时段</el-button>
    <div v-if="calendar" data-testid="availability-calendar"><p>
      {{ labels[calendar.coverageStatus] || calendar.coverageStatus }} · 查询时间 {{ display(calendar.checkedAt) }}</p>
      <h3>来源覆盖区间</h3>
      <p v-if="!calendar.coverageIntervals.length">当前时段没有有效覆盖证明，空白范围不能视为空闲。</p>
      <el-table :data="calendar.coverageIntervals">
        <el-table-column label="开始">
          <template #default="{row}">{{ display(row.from) }}</template>
        </el-table-column>
        <el-table-column label="结束">
          <template #default="{row}">{{ display(row.to) }}</template>
        </el-table-column>
        <el-table-column label="覆盖状态">
          <template #default="{row}">{{ labels[row.coverageStatus] || row.coverageStatus }}</template>
        </el-table-column>
      </el-table>
      <h3>开放规则</h3>
      <p v-if="!calendar.openingRules.length">尚未配置开放规则。</p>
      <el-table :data="calendar.openingRules">
        <el-table-column label="星期">
          <template #default="{row}">星期{{ ['一', '二', '三', '四', '五', '六', '日'][row.weekday - 1] }}</template>
        </el-table-column>
        <el-table-column prop="startLocal" label="开始时刻"/>
        <el-table-column prop="endLocal" label="结束时刻"/>
        <el-table-column prop="validFrom" label="生效日期"/>
        <el-table-column prop="validTo" label="结束日期"/>
        <el-table-column label="状态">
          <template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template>
        </el-table-column>
      </el-table>
      <h3>外部占用</h3>
      <p v-if="!calendar.intervals.length">未发现外部占用；仍需结合覆盖状态和开放规则判断。</p>
      <el-table :data="calendar.intervals">
        <el-table-column label="开始">
          <template #default="{row}">{{ display(row.startAt) }}</template>
        </el-table-column>
        <el-table-column label="结束">
          <template #default="{row}">{{ display(row.endAt) }}</template>
        </el-table-column>
        <el-table-column label="类别">
          <template #default>外部占用</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
