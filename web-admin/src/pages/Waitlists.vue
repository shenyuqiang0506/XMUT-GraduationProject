<script setup lang="ts">
/**
 * 本人候补与固定组合登记。使用上海墙上时刻，不在表单中推断学校规则或来源完整性。
 * 服务端返回版本和可执行动作；未知写入结果保留幂等键，竞争后刷新详情，不自动确认。
 */
import {ref, computed, onMounted, watch} from 'vue'
import {useRouter, useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import {DATA_MODE} from '../api/dataMode'
import {useSession} from '../stores/session'
import {usePagedOptions} from '../composables/usePagedOptions'
import ShanghaiDateTime from '../components/ShanghaiDateTime.vue'
import {shanghaiRange} from '../utils/time'
import type {Page, Resource, WaitlistSummary, WaitlistDetail, WaitlistStatus} from '../types'

const route = useRoute()
const session = useSession(), router = useRouter(), keys = mutationKeys()
const busy = ref(false), error = ref(''), items = ref<WaitlistSummary[]>([]), page = ref(1), total = ref(0),
    status = ref('')
const createDialog = ref(false), detailDialog = ref(false), selected = ref<WaitlistDetail>(), reason = ref('')
const name = ref(''), description = ref(''), start = ref(''), end = ref(''), participants = ref(20), venueId = ref(''),
    equipmentIds = ref<string[]>([]), features = ref<string[]>([])
const labels: Record<WaitlistStatus, string> = {
  WAITING: '排队中',
  OFFERED: '待本人确认',
  CONFIRMED: '已转待审批申请',
  EXPIRED: '已过期',
  CANCELLED: '已取消'
}
const {
  items: resources,
  loading,
  hasMore,
  more
} = usePagedOptions<Resource>(p => api<Page<Resource>>('/resources?dataMode=' + DATA_MODE + '&size=100&page=' + p))
const venues = computed(() => resources.value.filter(r => r.resourceType === 'VENUE' && r.platformStatus === 'OPEN'))
const venue = computed(() => venues.value.find(r => r.id === venueId.value))
const devices = computed(() => resources.value.filter(r => r.resourceType === 'EQUIPMENT' && r.platformStatus === 'OPEN' && r.campus === venue.value?.campus && r.approvalGroupId === venue.value?.approvalGroupId))
const display = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'}) : '—'

async function rows() {
  const data = await api<Page<WaitlistSummary>>('/waitlists?' + new URLSearchParams({
    status: status.value,
    page: String(page.value),
    size: '20'
  }));
  items.value = data.items;
  total.value = data.total
}

async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await rows()
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function filter() {
  page.value = 1;
  await load()
}

async function openCreate() {
  if (busy.value) return;
  error.value = '';
  createDialog.value = true;
  try {
    await more()
  } catch (e) {
    error.value = explain(e)
  }
}

async function loadOptions() {
  try {
    await more()
  } catch (e) {
    error.value = explain(e)
  }
}

async function create() {
  if (busy.value) return
  if (!name.value.trim() || !venueId.value) {
    error.value = '请填写活动名称并选择场地';
    return
  }
  let payload: unknown
  try {
    payload = {
      activityName: name.value.trim(),
      description: description.value.trim() || null,
      participantCount: participants.value, ...shanghaiRange(start.value, end.value, true),
      venueId: venueId.value,
      equipmentIds: [...equipmentIds.value],
      requiredFeatures: [...features.value]
    }
  } catch (e) {
    error.value = explain(e);
    return
  }
  busy.value = true;
  error.value = ''
  try {
    await api<WaitlistDetail>('/waitlists', 'POST', payload, keys.key('WAITLIST_CREATE', payload));
    keys.done('WAITLIST_CREATE', payload);
    createDialog.value = false;
    name.value = '';
    description.value = '';
    ElMessage.success('已加入候补，等待整组资源可用');
    page.value = 1;
    await rows()
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function detail(id: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  selected.value = undefined;
  reason.value = '';
  detailDialog.value = true
  try {
    selected.value = await api<WaitlistDetail>('/waitlists/' + id)
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

async function act(action: string) {
  const current = selected.value;
  if (!current || busy.value) return
  const payload = {expectedVersion: current.version, reason: reason.value.trim() || null},
      scope = action + ':' + current.waitlistId
  busy.value = true;
  error.value = ''
  try {
    selected.value = await api<WaitlistDetail>('/waitlists/' + current.waitlistId + '/' + action.toLowerCase(), 'POST', payload, keys.key(scope, payload));
    keys.done(scope, payload);
    ElMessage.success(action === 'CONFIRM' ? '候补已转为待审批申请' : '候补已取消');
    await rows()
  } catch (e) {
    error.value = explain(e);
    try {
      selected.value = await api<WaitlistDetail>('/waitlists/' + current.waitlistId)
    } catch {/* 保留原操作错误。 */
    }
  } finally {
    busy.value = false
  }
}

function application() {
  detailDialog.value = false;
  router.push('/applications' + (selected.value?.applicationId ? '?id=' + selected.value.applicationId : ''))
}

onMounted(async () => {
  await load();
  if (typeof route.query.id === 'string' && /^[1-9]\d*$/.test(route.query.id)) await detail(route.query.id)
})
watch(() => route.query.id, id => {
  if (typeof id === 'string' && /^[1-9]\d*$/.test(id)) detail(id)
})
</script>
<template>
  <h1>我的候补</h1>
  <p class="lead">相同场地、设备和时段按组内顺序等待；收到确认机会后仍需提交审批。</p>
  <el-alert title="排队时不占资源。获得确认机会后整组暂占，到期未确认会释放并交给下一位符合条件的人。" type="info"
            :closable="false"/>
  <div class="card">
    <div class="toolbar">
      <el-select v-model="status" aria-label="候补状态" :disabled="busy">
        <el-option label="全部状态" value=""/>
        <el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value"/>
      </el-select>
      <el-button :loading="busy" @click="filter">查询</el-button>
      <el-button v-if="session.applicant" type="primary" class="right" :disabled="busy" @click="openCreate">
        登记固定组合候补
      </el-button>
    </div>
    <el-table :data="items" data-testid="waitlist-list">
      <el-table-column prop="activityName" label="活动" min-width="180"/>
      <el-table-column label="状态" min-width="150">
        <template #default="{row}">{{ labels[row.status as WaitlistStatus] }}</template>
      </el-table-column>
      <el-table-column prop="queuePosition" label="同组位置" min-width="100"/>
      <el-table-column label="开始（上海时间）" min-width="165">
        <template #default="{row}">{{ display(row.startAt) }}</template>
      </el-table-column>
      <el-table-column label="确认截止" min-width="165">
        <template #default="{row}">{{ display(row.offerExpiresAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{row}">
          <el-button text :disabled="busy" @click="detail(row.waitlistId)">查看详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" :disabled="busy"
                   layout="prev,pager,next,total" @current-change="load"/>
  </div>
  <p v-if="error&&!createDialog&&!detailDialog" class="error" role="alert">{{ error }}</p>
  <el-dialog v-model="createDialog" title="登记固定组合候补" width="720px" :show-close="!busy"
             :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <el-form label-position="top" :disabled="busy" @submit.prevent="create">
      <el-form-item label="候补场地">
        <el-select v-model="venueId" filterable @change="equipmentIds=[]">
          <el-option v-for="r in venues" :key="r.id" :label="r.name" :value="r.id"/>
        </el-select>
      </el-form-item>
      <el-form-item label="配套设备">
        <el-select v-model="equipmentIds" multiple filterable :disabled="!venueId">
          <el-option v-for="r in devices" :key="r.id" :label="r.name" :value="r.id"/>
        </el-select>
      </el-form-item>
      <el-button v-if="hasMore" :loading="loading" @click="loadOptions">加载更多资源</el-button>
      <el-form-item label="开始时间">
        <ShanghaiDateTime v-model="start"/>
      </el-form-item>
      <el-form-item label="结束时间">
        <ShanghaiDateTime v-model="end"/>
      </el-form-item>
      <p>时间按上海时区解释，需位于同一自然日。</p>
      <el-form-item label="活动人数">
        <el-input-number v-model="participants" :min="1" :max="100000" :precision="0"/>
      </el-form-item>
      <el-form-item label="必要设施">
        <el-select v-model="features" multiple>
          <el-option label="投影" value="PROJECTOR"/>
          <el-option label="白板" value="WHITEBOARD"/>
          <el-option label="网络" value="NETWORK"/>
        </el-select>
      </el-form-item>
      <el-form-item label="活动名称">
        <el-input v-model="name" maxlength="200"/>
      </el-form-item>
      <el-form-item label="活动说明">
        <el-input v-model="description" type="textarea" maxlength="2000"/>
      </el-form-item>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <el-button native-type="submit" type="primary" :loading="busy">确认加入候补</el-button>
    </el-form>
  </el-dialog>
  <el-dialog v-model="detailDialog" title="候补详情" width="720px" :show-close="!busy" :close-on-click-modal="!busy"
             :close-on-press-escape="!busy">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="selected" data-testid="waitlist-detail">
      <h2>{{ selected.activityName }} · {{ labels[selected.status] }}</h2>
      <p>{{ display(selected.startAt) }} 至 {{ display(selected.endAt) }}（上海时间）</p>
      <p v-if="selected.queuePosition">同组位置：{{ selected.queuePosition }}，仅表示相同固定组合内的顺序。</p>
      <el-alert v-if="selected.status==='OFFERED'"
                :title="'请在 '+display(selected.offerExpiresAt)+' 前确认；确认后仍需审批。'" type="warning"
                :closable="false"/>
      <p>{{ selected.description || '未填写说明' }}</p>
      <p v-if="selected.lastReason">处理说明：{{ selected.lastReason }}</p>
      <el-table :data="selected.resources">
        <el-table-column prop="name" label="资源" min-width="170"/>
        <el-table-column prop="id" label="编号" min-width="100"/>
      </el-table>
      <el-input v-if="selected.allowedActions.includes('CANCEL')" v-model="reason" type="textarea" maxlength="500"
                placeholder="取消说明（可选）"/>
      <div class="dialog-actions">
        <el-button v-if="selected.allowedActions.includes('CONFIRM')" type="primary" :loading="busy"
                   @click="act('CONFIRM')">确认候补并提交审批
        </el-button>
        <el-button v-if="selected.allowedActions.includes('CANCEL')" :loading="busy" @click="act('CANCEL')">取消候补
        </el-button>
        <el-button v-if="selected.applicationId" @click="application">查看关联申请</el-button>
      </div>
    </div>
  </el-dialog>
</template>
