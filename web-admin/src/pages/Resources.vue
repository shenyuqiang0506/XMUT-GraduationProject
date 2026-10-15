<script setup lang="ts">
/**
 * 资源目录列表、详情、编辑及审批组配置页面。
 * 来源可借用与平台开放分别显示；没有具体时间窗口时，目录状态不能证明资源可预约。
 */
import {computed, ref, onMounted, onBeforeUnmount} from 'vue'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import {DATA_MODE} from '../api/dataMode'
import {stringObject} from '../utils/forms'
import {useSession} from '../stores/session'
import type {Resource, Group, Page, User} from '../types'
import AppIcon from '../components/AppIcon.vue'

const session = useSession(), keys = mutationKeys(), rows = ref<Resource[]>([]), total = ref(0), page = ref(1),
    busy = ref(false), error = ref(''), groups = ref<Group[]>([])
const filters = ref({campus: '', building: '', type: '', capacityMin: ''}), dialog = ref(false),
    editing = ref<Resource | null>(null), saving = ref(false), detail = ref<Resource | null>(null),
    groupDialog = ref(false), groupName = ref(''), membersGroup = ref(''), members = ref<string[]>([]),
    managers = ref<User[]>([])
const blank = () => ({
  resourceType: 'VENUE',
  sourceSystem: 'MANUAL',
  sourceId: '',
  name: '',
  campus: '',
  building: '',
  floorNo: '',
  category: '',
  subcategory: '',
  capacity: null as number | null,
  examCapacity: null as number | null,
  area: null as number | null,
  sourceBorrowable: 'UNKNOWN',
  platformStatus: 'DRAFT',
  approvalGroupId: null as string | null,
  dataMode: DATA_MODE as Resource['dataMode'],
  remark: '',
  features: {} as Record<string, string>,
  assetCode: '',
  equipmentType: '',
  conditionStatus: 'GOOD'
})
const form = ref(blank()), featureText = ref('{}')
const states: Record<string, string> = {DRAFT: '草稿', OPEN: '已开放', CLOSED: '已关闭', RETIRED: '已退役'},
    borrow: Record<string, string> = {YES: '来源可借', NO: '来源不可借', UNKNOWN: '来源未确认'}
const pageSummary = computed(() => [
  {label: '匹配资源总数', value: total.value, icon: 'grid', unit: '项'},
  {label: '本页场地', value: rows.value.filter(r => r.resourceType === 'VENUE').length, icon: 'campus', unit: '处'},
  {
    label: '本页设备',
    value: rows.value.filter(r => r.resourceType === 'EQUIPMENT').length,
    icon: 'equipment',
    unit: '件'
  },
  {label: '本页已开放', value: rows.value.filter(r => r.platformStatus === 'OPEN').length, icon: 'check', unit: '项'},
])

function resetFilters() {
  filters.value = {campus: '', building: '', type: '', capacityMin: ''}
  page.value = 1
  load()
}

// 请求编号区分 A→B→A 的连续查询；仅比较筛选值无法识别第一次 A 的迟到响应。
let listRequest = 0
onBeforeUnmount(() => {
  listRequest++
})

// 查询参数在发送前固定，只有最后发起的请求可以更新列表、错误和加载状态。
async function load() {
  const request = ++listRequest
  busy.value = true;
  error.value = '';
  try {
    const q = new URLSearchParams({page: String(page.value), size: '20', dataMode: DATA_MODE, ...filters.value});
    const data = await api<Page<Resource>>('/resources?' + q);
    if (request !== listRequest) return
    rows.value = data.items;
    total.value = data.total
  } catch (e) {
    if (request === listRequest) error.value = explain(e)
  } finally {
    if (request === listRequest) busy.value = false
  }
}

// 只有管理角色需要审批组列表，普通用户不发起该管理请求。
async function loadGroups() {
  if (session.manager) groups.value = (await api<{ items: Group[] }>('/admin/approval-groups')).items
}

onMounted(() => {
  load();
  loadGroups().catch(e => error.value = explain(e))
})

// 编辑时复制记录并补齐表单空值，不直接修改原列表；新增采用草稿状态。
function open(row?: Resource) {
  editing.value = row || null;
  form.value = row ? {
    ...blank(), ...row,
    campus: row.campus || '',
    building: row.building || '',
    floorNo: row.floorNo || '',
    category: row.category || '',
    subcategory: row.subcategory || '',
    remark: row.remark || '',
    assetCode: row.assetCode || '',
    equipmentType: row.equipmentType || '',
    conditionStatus: row.conditionStatus || 'GOOD'
  } : blank();
  featureText.value = JSON.stringify(form.value.features, null, 2);
  dialog.value = true
}

// 新增提交完整模型；编辑剔除不可变来源字段及只读状态，并提交读取时的资源版本。
// 请求未明确成功时保留幂等键，成功后刷新列表而非在本地推测最终版本。
async function save() {
  saving.value = true;
  try {
    const features = stringObject(featureText.value, '设施配置');
    const {id, version, availability, coverageStatus, ...all} = form.value as typeof form.value & Partial<Resource>;
    let payload: unknown = all, path = '/admin/resources', method = 'POST';
    if (editing.value) {
      path += '/' + editing.value.id;
      method = 'PATCH';
      const {sourceId, sourceSystem, dataMode, resourceType, assetCode, equipmentType, ...fields} = all;
      payload = {...fields, features, expectedVersion: editing.value.version}
    } else payload = {...all, features};
    await api(path, method, payload, keys.key(path, payload));
    keys.done(path, payload);
    dialog.value = false;
    ElMessage.success('资源已保存');
    await load()
  } catch (e) {
    ElMessage.error(explain(e))
  } finally {
    saving.value = false
  }
}

// 详情重新请求后端，以当前权限和最新记录为准，不直接复用可能过时的列表对象。
async function show(row: Resource) {
  try {
    detail.value = await api<Resource>('/resources/' + row.id)
  } catch (e) {
    ElMessage.error(explain(e))
  }
}

async function groupCreate() {
  const payload = {name: groupName.value};
  try {
    await api('/admin/approval-groups', 'POST', payload, keys.key('group', payload));
    keys.done('group', payload);
    groupName.value = '';
    await loadGroups();
    ElMessage.success('审批组已创建')
  } catch (e) {
    ElMessage.error(explain(e))
  }
}

// 为系统管理员加载审批组和当前页中的有效管理账号；最终成员资格仍由后端实时核验。
async function openGroups() {
  try {
    await loadGroups();
    managers.value = (await api<Page<User>>('/users?size=100')).items.filter(u => u.status === 'ACTIVE' && u.roles.some(r => r === 'RESOURCE_ADMIN' || r === 'SYSTEM_ADMIN'));
    groupDialog.value = true
  } catch (e) {
    ElMessage.error(explain(e))
  }
}

// 切换审批组时复制其成员集合，避免保存前误改其他组的本地展示数据。
function selectGroup() {
  members.value = [...(groups.value.find(g => g.id === membersGroup.value)?.memberUserIds || [])]
}

// 整体提交成员 ID 集合，空集合表示清空；成功后重新拉取组成员状态。
async function saveMembers() {
  const payload = {userIds: members.value}, path = '/admin/approval-groups/' + membersGroup.value + '/members';
  try {
    await api(path, 'PUT', payload, keys.key(path, payload));
    keys.done(path, payload);
    await loadGroups();
    ElMessage.success('组成员已保存')
  } catch (e) {
    ElMessage.error(explain(e))
  }
}
</script>
<template>
  <div class="page-heading">
    <div><span class="eyebrow">发现校园里的好资源</span>
      <h1>资源目录</h1>
      <p class="lead">浏览场地与设备，找到适合下一次活动的空间。</p></div>
    <router-link to="/availability" class="text-action">查找可用时段
      <AppIcon name="arrow" :size="18"/>
    </router-link>
  </div>
  <section class="catalog-summary" aria-label="资源统计">
    <div v-for="item in pageSummary" :key="item.label" class="catalog-stat">
      <span class="stat-icon"><AppIcon :name="item.icon" :size="21"/></span>
      <div><span>{{ item.label }}</span><strong>{{
          busy || error ? '—' : item.value.toLocaleString('zh-CN')
        }}<small>{{ item.unit }}</small></strong></div>
    </div>
  </section>
  <div class="catalog-notice">
    <AppIcon name="info" :size="18"/>
    <p>目录展示来源与管理状态，具体时段能否预约，请以可用时段查询及审批结果为准。</p></div>
  <section class="card catalog-card" aria-labelledby="catalog-title">
    <div class="section-heading">
      <div><h2 id="catalog-title">全部资源<span class="count-badge">{{ busy || error ? '—' : total }}</span></h2>
        <p>按校区、类型或容量筛选资源</p></div>
      <div class="section-actions">
        <el-button v-if="session.admin" @click="openGroups">审批组</el-button>
        <el-button v-if="session.manager" type="primary" @click="open()">
          <AppIcon name="plus" :size="16"/>
          新增资源
        </el-button>
      </div>
    </div>
    <form class="catalog-filters" @submit.prevent="page=1;load()">
      <label><span>校区</span>
        <el-input v-model="filters.campus" aria-label="筛选校区" placeholder="校区" clearable/>
      </label>
      <label><span>楼栋</span>
        <el-input v-model="filters.building" aria-label="筛选楼栋" placeholder="楼栋" clearable/>
      </label>
      <label><span>资源类型</span>
        <el-select v-model="filters.type" aria-label="筛选资源类型" placeholder="资源类型" clearable>
          <el-option label="场地" value="VENUE"/>
          <el-option label="设备" value="EQUIPMENT"/>
        </el-select>
      </label>
      <label><span>最小容量</span>
        <el-input v-model="filters.capacityMin" aria-label="筛选最小容量" placeholder="最小容量" type="number" min="0"/>
      </label>
      <div class="filter-actions">
        <el-button type="primary" native-type="submit">
          <AppIcon name="search" :size="16"/>
          查询
        </el-button>
        <el-button text @click="resetFilters">重置</el-button>
      </div>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <el-table :data="rows" v-loading="busy">
      <el-table-column label="资源名称" min-width="190">
        <template #default="{row}">
          <div class="resource-name-cell"><span class="resource-type-icon"
                                                :class="{equipment: row.resourceType === 'EQUIPMENT'}"><AppIcon
              :name="row.resourceType === 'VENUE' ? 'campus' : 'equipment'"/></span>
            <div>
              <el-button link type="primary" @click="show(row)">{{ row.name }}</el-button>
              <div class="small resource-source">{{ row.sourceSystem }} / {{ row.sourceId }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="位置" min-width="160">
        <template #default="{row}">{{ row.campus || '—' }} / {{ row.building || '—' }} {{ row.floorNo || '' }}
        </template>
      </el-table-column>
      <el-table-column label="容量" width="90">
        <template #default="{row}"><span class="capacity-value">{{ row.capacity ?? '—' }}</span><span
            v-if="row.capacity != null" class="capacity-unit">人</span></template>
      </el-table-column>
      <el-table-column label="来源与状态" min-width="155">
        <template #default="{row}">
          <span class="resource-state"
                :class="'state-' + row.platformStatus.toLowerCase()"><i/>{{ states[row.platformStatus] }}</span>
          <div class="small">{{ borrow[row.sourceBorrowable] }}</div>
        </template>
      </el-table-column>
      <el-table-column label="时段可用性" width="130">
        <template #default="{row}"><span class="availability-label">{{
            row.availability === 'CLOSED' ? '未开放' : '待查询'
          }}</span></template>
      </el-table-column>
      <template #empty>
        <div v-if="!busy" class="empty-state"><span class="empty-state-icon"><AppIcon :name="error ? 'info' : 'search'"
                                                                                      :size="28"/></span>
          <strong>{{ error ? '资源暂时未能加载' : '没有找到匹配的资源' }}</strong>
          <p>{{ error ? '请稍后点击查询重试。' : '试试调整校区、资源类型或容量条件。' }}</p>
          <el-button v-if="!error" text type="primary" @click="resetFilters">清除筛选条件
            <AppIcon name="arrow" :size="16"/>
          </el-button>
        </div>
      </template>
      <el-table-column v-if="session.manager" label="操作" width="80">
        <template #default="{row}">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next"
                   @current-change="load"/>
  </section>
  <el-dialog v-model="dialog" :title="editing?'编辑资源':'新增资源'" width="720px" destroy-on-close>
    <el-form label-position="top" @submit.prevent="save">
      <div class="twocol">
        <el-form-item label="资源名称">
          <el-input v-model="form.name" maxlength="200"/>
        </el-form-item>
        <el-form-item label="资源类型">
          <el-select v-model="form.resourceType" :disabled="!!editing">
            <el-option label="场地" value="VENUE"/>
            <el-option label="设备" value="EQUIPMENT"/>
          </el-select>
        </el-form-item>
        <el-form-item label="来源系统">
          <el-input v-model="form.sourceSystem" :disabled="!!editing" maxlength="32"/>
        </el-form-item>
        <el-form-item label="来源编号（保留前导零）">
          <el-input v-model="form.sourceId" :disabled="!!editing" maxlength="64"/>
        </el-form-item>
        <el-form-item label="审批组">
          <el-select v-model="form.approvalGroupId" :disabled="!!editing&&!session.admin" clearable
                     @clear="form.approvalGroupId=null">
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
          </el-select>
        </el-form-item>
        <el-form-item v-for="field in ['campus','building','floorNo','category','subcategory'] as const" :key="field"
                      :label="({campus:'校区',building:'楼栋',floorNo:'楼层',category:'分类',subcategory:'子分类'})[field]">
          <el-input v-model="form[field]"/>
        </el-form-item>
        <el-form-item label="容量（留空为未知）">
          <el-input-number v-model="form.capacity" :min="0" :precision="0"/>
        </el-form-item>
        <el-form-item label="考试容量">
          <el-input-number v-model="form.examCapacity" :min="0" :precision="0"/>
        </el-form-item>
        <el-form-item label="面积（㎡）">
          <el-input-number v-model="form.area" :min="0" :precision="2"/>
        </el-form-item>
        <el-form-item label="来源可借状态">
          <el-select v-model="form.sourceBorrowable">
            <el-option label="未知" value="UNKNOWN"/>
            <el-option label="可借" value="YES"/>
            <el-option label="不可借" value="NO"/>
          </el-select>
        </el-form-item>
        <el-form-item label="平台状态">
          <el-select v-model="form.platformStatus">
            <el-option v-for="(name,value) in states" :key="value" :label="name" :value="value"/>
          </el-select>
        </el-form-item>
        <template v-if="form.resourceType==='EQUIPMENT'">
          <el-form-item label="资产编号">
            <el-input v-model="form.assetCode" :disabled="!!editing" maxlength="64"/>
          </el-form-item>
          <el-form-item label="设备类型">
            <el-input v-model="form.equipmentType" :disabled="!!editing" maxlength="32"/>
          </el-form-item>
          <el-form-item label="设备状态">
            <el-select v-model="form.conditionStatus">
              <el-option label="良好" value="GOOD"/>
              <el-option label="损坏" value="DAMAGED"/>
              <el-option label="维修中" value="MAINTENANCE"/>
            </el-select>
          </el-form-item>
        </template>
      </div>
      <el-form-item label="设施 JSON（如 PROJECTOR、WHITEBOARD）">
        <el-input v-model="featureText" type="textarea" :rows="3"/>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="4000"/>
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving">保存资源</el-button>
    </el-form>
  </el-dialog>
  <el-drawer :model-value="!!detail" title="资源详情" size="460px" @close="detail=null">
    <template v-if="detail"><h2>{{ detail.name }}</h2>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="来源">{{ detail.sourceSystem }} / {{ detail.sourceId }}</el-descriptions-item>
        <el-descriptions-item label="位置">{{ detail.campus || '未知' }} / {{ detail.building || '未知' }}
        </el-descriptions-item>
        <el-descriptions-item label="容量">{{ detail.capacity ?? '未知' }}</el-descriptions-item>
        <el-descriptions-item label="平台状态">{{ states[detail.platformStatus] }}</el-descriptions-item>
        <el-descriptions-item label="设施">
          {{ Object.entries(detail.features).map(([k, v]) => k + ': ' + v).join('；') || '未登记' }}
        </el-descriptions-item>
        <el-descriptions-item label="占用覆盖">未知</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
    </template>
  </el-drawer>
  <el-dialog v-model="groupDialog" title="审批组管理" width="560px">
    <el-form label-position="top">
      <el-form-item label="创建审批组">
        <el-input v-model="groupName" maxlength="100"/>
        <el-button @click="groupCreate">创建审批组</el-button>
      </el-form-item>
      <el-form-item label="选择审批组">
        <el-select v-model="membersGroup" @change="selectGroup">
          <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id"/>
        </el-select>
      </el-form-item>
      <el-form-item label="授权组成员">
        <el-select v-model="members" multiple :disabled="!membersGroup">
          <el-option v-for="u in managers" :key="u.id" :label="u.displayName+' / '+u.username" :value="u.id"/>
        </el-select>
      </el-form-item>
      <el-button type="primary" :disabled="!membersGroup" @click="saveMembers">保存组成员</el-button>
      <p class="small">只显示当前查询到的前 100 个账号中的有效管理员；成员授权由服务端再次校验。</p></el-form>
  </el-dialog>
</template>
