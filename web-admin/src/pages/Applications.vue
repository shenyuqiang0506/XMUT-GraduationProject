<script setup lang="ts">
/**
 * 本人申请与审批列表共用展示；管理视图使用独立路由包装，切换后重新创建状态。
 * 详情提供服务端 allowedActions，提交携带版本与幂等键；冲突后刷新详情并由用户重新决定。
 */
import {ref, onMounted, watch} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import type {ApplicationSummary, ApplicationDetail, ApplicationStatus, Page} from '../types'

const route=useRoute()
const props = defineProps<{administrative?: boolean}>()
const items = ref<ApplicationSummary[]>([]), total = ref(0), page = ref(1), status = ref('')
const selected = ref<ApplicationDetail>(), dialog = ref(false), reason = ref(''), busy = ref(false), error = ref('')
const keys = mutationKeys()
const labels: Record<ApplicationStatus,string> = {
  PENDING_APPROVAL:'待审批', APPROVED:'已批准', CONFLICT_REVIEW:'冲突待复核', COMPLETED:'已完成',
  REJECTED:'已驳回', EXPIRED:'已过期', CANCELLED:'已取消'
}
const actionLabels: Record<string,string> = {CANCEL:'取消申请',APPROVE:'批准申请',REJECT:'驳回申请',RESOLVE_CANCEL:'冲突处置取消',REVALIDATE:'重新验证恢复批准'}
function display(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) : '—' }

async function rows() {
  const endpoint = props.administrative ? '/admin/applications' : '/applications'
  const data = await api<Page<ApplicationSummary>>(endpoint+'?'+new URLSearchParams({status:status.value,page:String(page.value),size:'20'}))
  items.value=data.items; total.value=data.total
}
async function load() {
  if (busy.value) return
  busy.value=true; error.value=''
  try { await rows() } catch(e) { error.value=explain(e) } finally { busy.value=false }
}
async function filter() { page.value=1; await load() }
onMounted(async()=>{await load();if(typeof route.query.id==='string'&&/^[1-9]\d*$/.test(route.query.id))await detail(route.query.id)})

async function detail(id: string) {
  if (busy.value) return
  busy.value=true; error.value=''; selected.value=undefined; reason.value=''; dialog.value=true
  try { selected.value=await api<ApplicationDetail>('/applications/'+id) }
  catch(e) { error.value=explain(e) } finally { busy.value=false }
}

async function act(action: string) {
  const current=selected.value
  if (!current || busy.value) return
  if (['REJECT','RESOLVE_CANCEL','REVALIDATE'].includes(action) && !reason.value.trim()) {
    error.value='请填写驳回或冲突处置理由'; return
  }
  const resolve=['RESOLVE_CANCEL','REVALIDATE'].includes(action)
  const payload={expectedVersion:current.version,reason:reason.value.trim() || null,
    ...(resolve ? {action:action==='RESOLVE_CANCEL'?'CANCEL':'REVALIDATE'} : {})}
  const path=action==='CANCEL' ? '/applications/'+current.applicationId+'/cancel'
    : '/admin/applications/'+current.applicationId+'/'+(resolve?'resolve-conflict':action.toLowerCase())
  const scope=action+':'+current.applicationId
  busy.value=true; error.value=''
  try {
    selected.value=await api<ApplicationDetail>(path,'POST',payload,keys.key(scope,payload))
    keys.done(scope,payload); reason.value=''; ElMessage.success('申请状态已更新')
    await rows()
  } catch(e) {
    error.value=explain(e)
    // 刷新当前版本但不自动重新审批；未知结果的幂等键保留，避免重复执行。
    try { selected.value=await api<ApplicationDetail>('/applications/'+current.applicationId) } catch { /* 保留原操作错误提示。 */ }
  } finally { busy.value=false }
}
watch(()=>route.query.id,id=>{if(typeof id==='string'&&/^[1-9]\d*$/.test(id))detail(id)})
</script>
<template>
  <h1>{{ administrative ? '预约审批' : '我的申请' }}</h1>
  <p class="lead">{{ administrative ? '处理授权审批组的联合申请，批准前重新校验整组资源。' : '查看场地与设备的联合申请、审批结果和暂占期限。' }}</p>
  <div class="card">
    <el-select v-model="status" placeholder="全部状态" :disabled="busy" style="width:180px">
      <el-option label="全部状态" value=""/>
      <el-option v-for="(label,value) in labels" :key="value" :label="label" :value="value"/>
    </el-select>
    <el-button :loading="busy" @click="filter">查询</el-button>
    <el-table :data="items" data-testid="application-list">
      <el-table-column prop="activityName" label="活动"/>
      <el-table-column label="状态"><template #default="{row}">{{ labels[row.status as ApplicationStatus] }}</template></el-table-column>
      <el-table-column label="开始（上海时间）"><template #default="{row}">{{ display(row.startAt) }}</template></el-table-column>
      <el-table-column label="结束"><template #default="{row}">{{ display(row.endAt) }}</template></el-table-column>
      <el-table-column label="暂占截止"><template #default="{row}">{{ display(row.holdExpiresAt) }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button text :disabled="busy" @click="detail(row.applicationId)">查看详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" :disabled="busy" layout="prev,pager,next,total" @current-change="load"/>
  </div>
  <p v-if="error && !dialog" class="error" role="alert">{{ error }}</p>
  <el-dialog v-model="dialog" title="联合预约申请" width="760px" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="selected" data-testid="application-detail">
      <h2>{{ selected.activityName }} · {{ labels[selected.status] }}</h2>
      <p>申请人：{{ selected.applicant.displayName }}（{{ selected.applicant.username }}） · {{ selected.participantCount }} 人</p>
      <p>{{ display(selected.startAt) }} 至 {{ display(selected.endAt) }}（上海时间）</p>
      <p v-if="selected.holdExpiresAt">暂占截止：{{ display(selected.holdExpiresAt) }}，到期未批准会释放全部资源。</p>
      <el-alert v-if="selected.status==='CONFLICT_REVIEW'" title="资源或来源发生变化，该申请需要复核；当前不能视为正常可使用的预约。" type="warning" :closable="false"/>
      <p>{{ selected.description || '未填写活动说明' }}</p>
      <p v-if="selected.lastReason">最新处理说明：{{ selected.lastReason }}</p>
      <el-table :data="selected.resources">
        <el-table-column prop="name" label="资源"/><el-table-column label="类型"><template #default="{row}">{{ row.resourceType==='VENUE'?'场地':'设备' }}</template></el-table-column>
        <el-table-column prop="id" label="编号"/>
      </el-table>
      <h3>处理记录</h3>
      <p v-if="!selected.approvalRecords.length">尚未处理。</p>
      <el-table v-else :data="selected.approvalRecords">
        <el-table-column label="处理结果"><template #default="{row}">{{ labels[row.decision as ApplicationStatus] || row.decision }}</template></el-table-column>
        <el-table-column prop="reason" label="理由"/><el-table-column label="时间"><template #default="{row}">{{ display(row.createdAt) }}</template></el-table-column>
      </el-table>
      <el-input v-if="selected.allowedActions.length" v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="处理理由，驳回及冲突处置必须填写" :disabled="busy" data-testid="application-reason"/>
      <el-button v-for="action in selected.allowedActions" :key="action" :loading="busy" :type="action==='APPROVE'?'primary':'default'" @click="act(action)">{{ actionLabels[action] }}</el-button>
    </div>
  </el-dialog>
</template>
