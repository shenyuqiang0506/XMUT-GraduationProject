<script setup lang="ts">
/** 通知显示事件历史；点击后查询当前业务对象，不把晚到事件当作最新预约状态。 */
import {ref, onMounted, onUnmounted} from 'vue'
import {useRouter} from 'vue-router'
import {api, explain} from '../api/client'
import type {Page, Notice} from '../types'

const router = useRouter(), items = ref<Notice[]>([]), total = ref(0), page = ref(1), unread = ref(false),
    unreadCount = ref(0), busy = ref(false), error = ref('')
const display = (value: string) => new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai'})

async function rows() {
  const data = await api<Page<Notice> & {
    unreadCount: number
  }>('/notifications?' + new URLSearchParams({page: String(page.value), size: '20', unreadOnly: String(unread.value)}));
  items.value = data.items;
  total.value = data.total;
  unreadCount.value = data.unreadCount
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

async function read(item: Notice, open = false) {
  if (busy.value) return;
  busy.value = true;
  error.value = ''
  try {
    await api<Notice>('/notifications/' + item.notificationId + '/read', 'POST');
    await rows();
    if (open) await router.push((item.objectType === 'APPLICATION' ? '/applications' : '/waitlists') + '?id=' + item.objectId)
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  load();
  timer = setInterval(() => {
    if (!document.hidden) load()
  }, 20000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>
<template>
  <h1>站内通知</h1>
  <p class="lead">未读 {{ unreadCount }} 条。通知反映发生过的事件，当前预约状态以关联记录为准。</p>
  <div class="card">
    <div class="toolbar">
      <el-checkbox v-model="unread" :disabled="busy" @change="filter">只看未读</el-checkbox>
      <el-button class="right" :loading="busy" @click="load">刷新通知</el-button>
    </div>
    <el-table :data="items" data-testid="notifications-list">
      <el-table-column label="通知" min-width="280">
        <template #default="{row}"><strong>{{ row.title }}</strong>
          <p>{{ row.body }}</p></template>
      </el-table-column>
      <el-table-column label="事件时间（上海）" min-width="165">
        <template #default="{row}">{{ display(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="阅读状态" min-width="100">
        <template #default="{row}">{{ row.readAt ? '已读' : '未读' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button v-if="!row.readAt" text :disabled="busy" @click="read(row)">标为已读</el-button>
          <el-button text :disabled="busy" @click="read(row,true)">查看当前记录</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" :disabled="busy"
                   layout="prev,pager,next,total" @current-change="load"/>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>
