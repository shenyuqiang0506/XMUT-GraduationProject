<script setup lang="ts">
/**
 * 文件导入的预览与确认工作流。
 * 浏览器先检查文件大小及 JSON 参数，再上传文件、映射和来源上下文；服务端执行最终校验。
 * 只有对应当前输入且无阻断错误的预览批次才允许确认。
 */
import {ref, onMounted, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import {usePagedOptions} from '../composables/usePagedOptions'
import ShanghaiDateTime from '../components/ShanghaiDateTime.vue'
import {shanghaiRange, shanghaiInstant} from '../utils/time'
import {jsonObject, stringObject} from '../utils/forms'
import type {ImportBatch, Group, Resource, Page} from '../types'

// 日常业务默认使用真实来源；测试资料必须显式选择隔离模式。
const file = ref<File | null>(null), source = ref('CAMPUS_CATALOG'), mode = ref('REAL'), group = ref(''),
    mapping = ref('{}'), context = ref('{}'), batch = ref<ImportBatch | null>(null), busy = ref(false), error = ref(''),
    groups = ref<Group[]>([]), keys = mutationKeys()
const kind = ref('RESOURCE_CATALOG'), resourceIds = ref<string[]>([])
const from = ref(''), to = ref(''), captured = ref('')
const completeness = ref('PARTIAL'), description = ref('')
// 预览只对应当时的文件和参数；修改输入后必须重新核对，避免确认旧批次。
watch([source, mode, group, mapping, context, kind, resourceIds, from, to, captured, completeness, description], () => { batch.value = null }, {deep: true})
onMounted(async () => {
  try {
    groups.value = (await api<{ items: Group[] }>('/admin/approval-groups')).items
    await loadResources()
  } catch (e) {
    error.value = explain(e)
  }
})

// 资源选项与文件提交使用独立加载状态，失败不会跳过当前页。
const {items: resources, loading: resourceLoading, hasMore: resourceHasMore, more: loadNextResourcePage} =
    usePagedOptions<Resource>(page => api<Page<Resource>>('/resources?size=100&page=' + page))

async function loadResources() { await loadNextResourcePage() }

async function more() {
  error.value = ''
  try { await loadResources() }
  catch (e) { error.value = explain(e) }
}

// 导入类型或来源条件变化时，同时丢弃旧批次与已选资源范围。
function changed() {
  batch.value = null;
  resourceIds.value = []
}

// 更换文件后必须重新预览，不能沿用另一文件的批次确认按钮。
function selected(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] || null;
  batch.value = null;
  error.value = ''
}

// 将文件摘要与全部输入共同作为前端幂等内容；服务端仍独立计算摘要并保存受控文件。
// 目录导入附审批组上下文，时间导入附覆盖窗口、采集时间和明确资源范围。
async function preview() {
  if (busy.value) return
  busy.value = true;
  error.value = '';
  batch.value = null;
  try {
    if (!file.value) throw new Error('请选择导入文件');
    if (file.value.size > 2 * 1024 * 1024) throw new Error('文件不能超过 2 MB');
    const mappingData = stringObject(mapping.value, '字段映射'), extra = jsonObject(context.value, '查询与采集说明')
    let contextData: Record<string, unknown>
    if (kind.value === 'RESOURCE_CATALOG') {
      contextData = {...extra, ...(group.value ? {approvalGroupId: group.value} : {})}
    } else {
      const dates = from.value && to.value ? shanghaiRange(from.value, to.value) : undefined
      contextData = {
        ...extra, ...(from.value ? {from: dates?.startAt || shanghaiInstant(from.value, '覆盖开始')} : {}),
        ...(to.value ? {to: dates?.endAt || shanghaiInstant(to.value, '覆盖结束')} : {}),
        ...(captured.value ? {capturedAt: shanghaiInstant(captured.value, '来源采集时间')} : {}),
        completeness: completeness.value, ...(description.value ? {sourceDescription: description.value} : {}),
        ...(resourceIds.value.length ? {resourceIds: resourceIds.value} : {})
      }
    }
    // 完整覆盖必须显式选择资源范围，不能由文件缺失记录自动推断。
    const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.value.arrayBuffer()))).map(v => v.toString(16).padStart(2, '0')).join('');
    const payload = {
      digest,
      filename: file.value.name,
      source: source.value,
      mode: mode.value,
      kind: kind.value,
      mappingData,
      contextData
    }, data = new FormData();
    data.set('file', file.value);
    data.set('importType', kind.value);
    data.set('sourceSystem', source.value);
    data.set('dataMode', mode.value);
    data.set('fieldMapping', JSON.stringify(mappingData));
    data.set('queryContext', JSON.stringify(contextData));
    batch.value = await api<ImportBatch>('/admin/imports/preview', 'POST', data, keys.key('preview', payload));
    keys.done('preview', payload)
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

// 只确认已保存的 batchId；不重新上传文件。最终摘要、权限及行错误检查仍由服务端执行。
async function commit() {
  if (busy.value || !batch.value || batch.value.summary.errorRows > 0) return;
  busy.value = true;
  error.value = '';
  const id = batch.value.batchId;
  try {
    batch.value = await api<ImportBatch>('/admin/imports/' + id + '/commit', 'POST', undefined, keys.key('commit', id));
    keys.done('commit', id);
    ElMessage.success(kind.value === 'RESOURCE_CATALOG' ? '目录导入成功' : '来源时间数据导入成功')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}
</script>
<template><h1>资料导入</h1>
  <p class="lead">先核对文件、来源、覆盖范围与错误，再确认写入。支持 Excel / JSON，最多 2 MB、2,000 行。</p>
  <el-alert v-if="kind==='RESOURCE_CATALOG'" title="目录导入不建立占用或空闲覆盖，新增资源默认草稿。" type="info"
            :closable="false"/>
  <el-alert v-else
            title="PARTIAL 不能证明空闲。COMPLETE 必须明确资源、时间范围、来源依据与采集时间；完整占用快照才会替换范围内缺失记录。"
            type="warning" :closable="false"/>
  <div class="card">
    <el-form label-position="top" :disabled="busy" @submit.prevent="preview">
      <div class="twocol">
        <el-form-item label="导入内容">
          <el-select v-model="kind" :disabled="busy" @change="changed">
            <el-option label="资源目录" value="RESOURCE_CATALOG"/>
            <el-option label="来源占用快照" value="EXTERNAL_OCCUPANCY"/>
            <el-option label="已确认空闲区间" value="CONFIRMED_FREE_INTERVALS"/>
          </el-select>
        </el-form-item>
        <el-form-item label="来源系统">
          <el-input v-model="source" maxlength="32"/>
        </el-form-item>
        <el-form-item label="数据模式">
          <el-select v-model="mode">
            <el-option label="演示资料 DEMO" value="DEMO"/>
            <el-option label="真实来源 REAL" value="REAL"/>
          </el-select>
        </el-form-item>
        <el-form-item v-if="kind==='RESOURCE_CATALOG'" label="审批组">
          <el-select v-model="group" clearable>
            <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id"/>
          </el-select>
        </el-form-item>
        <el-form-item label="文件"><input data-testid="import-file" type="file" :disabled="busy" accept=".json,.xlsx,.xls"
                                          @change="selected"/></el-form-item>
      </div>
      <div v-if="kind!=='RESOURCE_CATALOG'" class="twocol">
        <el-form-item label="明确的资源范围">
          <el-select v-model="resourceIds" multiple filterable>
            <el-option v-for="r in resources.filter(r=>r.sourceSystem===source&&r.dataMode===mode)" :key="r.id"
                       :label="r.name+' · '+r.sourceId" :value="r.id"/>
          </el-select>
          <el-button v-if="resourceHasMore" :loading="resourceLoading" @click="more">加载更多资源</el-button>
        </el-form-item>
        <el-form-item label="覆盖完整性">
          <el-select v-model="completeness">
            <el-option label="部分 PARTIAL" value="PARTIAL"/>
            <el-option label="已确认完整 COMPLETE" value="COMPLETE"/>
          </el-select>
        </el-form-item>
        <p class="small">覆盖范围与采集时间均按上海时间（UTC+08:00）填写。</p>
        <el-form-item label="覆盖开始">
          <ShanghaiDateTime v-model="from"/>
        </el-form-item>
        <el-form-item label="覆盖结束">
          <ShanghaiDateTime v-model="to"/>
        </el-form-item>
        <el-form-item label="来源采集时间">
          <ShanghaiDateTime v-model="captured"/>
        </el-form-item>
        <el-form-item label="来源依据">
          <el-input v-model="description" maxlength="500" placeholder="填写来源和此次查询的筛选条件"/>
        </el-form-item>
      </div>
      <el-collapse>
        <el-collapse-item title="字段映射与来源说明（JSON）" name="advanced">
          <el-form-item label="字段映射：目标字段 → 原始列名">
            <el-input v-model="mapping" type="textarea" :rows="3"/>
          </el-form-item>
          <el-form-item label="查询与采集说明（sourceDescription、capturedAt 等）">
            <el-input v-model="context" type="textarea" :rows="3"/>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!file">预览导入</el-button>
    </el-form>
  </div>
  <div v-if="batch" class="card" data-testid="import-preview"><h2>预览结果 · 批次 {{ batch.batchId }}</h2>
    <p class="small">{{ batch.status === 'COMMITTED' ? '已确认写入' : '等待核对确认' }} · 文件 SHA-256
      {{ batch.fileHash }}</p>
    <div class="stats">
      <div
          v-for="(label,key) in {sourceRows:'文件实际行数',newRows:'预计新增',updateRows:'预计更新',errorRows:'阻断错误行'}"
          :key="key" class="stat">{{ label }}<strong>{{ batch.summary[key] }}</strong></div>
    </div>
    <el-table v-if="batch.errors.length" :data="batch.errors">
      <el-table-column prop="rowNumber" label="行号" width="80"/>
      <el-table-column prop="fieldName" label="字段" width="130"/>
      <el-table-column prop="code" label="错误码" width="190"/>
      <el-table-column prop="message" label="说明"/>
    </el-table>
    <el-button type="primary" :loading="busy" :disabled="batch.summary.errorRows>0||batch.status==='COMMITTED'"
               @click="commit">{{ kind === 'RESOURCE_CATALOG' ? '确认写入目录' : '确认写入来源时间数据' }}
    </el-button>
    <span v-if="batch.summary.errorRows>0" class="error"> 请修正文件后重新预览。</span></div>
</template>
