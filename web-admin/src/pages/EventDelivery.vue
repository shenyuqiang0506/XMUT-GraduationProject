<script setup lang="ts">
/** 运维摘要仅系统管理员可访问；检查失败原因后人工重试，保持原事件 ID 供消费者去重。 */
import {ref,onMounted} from 'vue'
import {ElMessage} from 'element-plus'
import {api,explain,mutationKeys} from '../api/client'
import type {EventDelivery,Page} from '../types'
interface DeadLetter {eventId:string|null;valid:boolean;eventType?:string;objectType?:string;objectId?:string;aggregateVersion?:number;error:string}
const items=ref<EventDelivery[]>([]),dead=ref<DeadLetter[]>([]),readyCount=ref(0),total=ref(0),page=ref(1),state=ref('FAILED'),reason=ref(''),busy=ref(false),error=ref('')
const keys=mutationKeys(),labels={PENDING:'等待投递',SENDING:'投递中',SENT:'已获发布确认',FAILED:'失败待处理'}
async function rows(){const data=await api<Page<EventDelivery>>('/admin/outbox?'+new URLSearchParams({status:state.value,page:String(page.value),size:'20'}));items.value=data.items;total.value=data.total}
async function load(){if(busy.value)return;busy.value=true;error.value='';try{await rows();const data=await api<{items:DeadLetter[];readyCount:number}>('/admin/notifications/dead-letters');dead.value=data.items;readyCount.value=data.readyCount}catch(e){error.value=explain(e)}finally{busy.value=false}}
async function filter(){page.value=1;await load()}
async function retry(item:EventDelivery){
 if(busy.value)return;if(!reason.value.trim()){error.value='请先填写检查原因和修复情况';return}
 const payload={expectedVersion:item.version,reason:reason.value.trim()},scope='OUTBOX_RETRY:'+item.id
 busy.value=true;error.value='';try{await api('/admin/outbox/'+item.id+'/retry','POST',payload,keys.key(scope,payload));keys.done(scope,payload);ElMessage.success('事件已重新进入投递流程');await rows()}catch(e){error.value=explain(e)}finally{busy.value=false}
}
async function replay(item:DeadLetter){
 if(busy.value||!item.valid||!item.eventId)return;if(!reason.value.trim()){error.value='请先填写检查原因和修复情况';return}
 const payload={reason:reason.value.trim()},scope='DEAD_REPLAY:'+item.eventId
 busy.value=true;error.value='';try{await api('/admin/notifications/dead-letters/'+item.eventId+'/replay','POST',payload,keys.key(scope,payload));keys.done(scope,payload);ElMessage.success('消息已重放，等待消费');const data=await api<{items:DeadLetter[];readyCount:number}>('/admin/notifications/dead-letters');dead.value=data.items;readyCount.value=data.readyCount}catch(e){error.value=explain(e)}finally{busy.value=false}
}
onMounted(load)
</script>
<template>
 <h1>消息投递管理</h1><p class="lead">检查当前环境的失败事件和死信，修复故障后重试；发布确认不代表用户已经阅读通知。</p>
 <div class="card"><el-form label-position="top"><el-form-item label="检查与修复说明"><el-input v-model="reason" type="textarea" maxlength="500" :rows="3" :disabled="busy"/></el-form-item></el-form>
  <el-select v-model="state" aria-label="投递状态" :disabled="busy"><el-option label="全部状态" value=""/><el-option v-for="(label,key) in labels" :key="key" :label="label" :value="key"/></el-select><el-button :loading="busy" @click="filter">查询</el-button>
  <el-table :data="items" data-testid="event-delivery-list"><el-table-column prop="eventType" label="事件"/><el-table-column prop="eventId" label="编号"/><el-table-column label="状态"><template #default="{row}">{{ labels[row.status as keyof typeof labels] }}</template></el-table-column><el-table-column prop="attempts" label="尝试次数"/><el-table-column prop="lastErrorCode" label="失败原因"/><el-table-column label="操作"><template #default="{row}"><el-button v-if="row.status==='FAILED'" :disabled="busy" @click="retry(row)">重新投递</el-button></template></el-table-column></el-table>
  <el-pagination v-model:current-page="page" :total="total" :page-size="20" :disabled="busy" layout="prev,pager,next,total" @current-change="load"/>
 </div><div class="card"><h2>死信检查 · 待处理 {{ readyCount }} 条</h2><p>最多预览 20 条；格式不正确的消息需要先排查，不能直接重放。</p>
  <el-button :loading="busy" @click="load">刷新检查</el-button><el-table :data="dead" data-testid="dead-letter-list"><el-table-column prop="eventId" label="编号"/><el-table-column prop="eventType" label="事件"/><el-table-column prop="error" label="说明"/><el-table-column label="操作"><template #default="{row}"><el-button v-if="row.valid" :disabled="busy" @click="replay(row)">修复后重放</el-button></template></el-table-column></el-table>
 </div><p v-if="error" class="error" role="alert">{{ error }}</p>
</template>
