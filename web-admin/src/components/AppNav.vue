<script setup lang="ts">
import {computed} from 'vue'
import {useSession} from '../stores/session'
import AppIcon from './AppIcon.vue'

const session = useSession()
defineEmits<{ navigate: [] }>()
const groups = computed(() => [
  {
    label: '资源与预约', items: [
      {path: '/resources', label: '资源目录', icon: 'grid'},
      {path: '/availability', label: '可用时段查询', icon: 'calendar'},
      ...(session.applicant ? [{path: '/ai', label: '智能资源查询', icon: 'spark'}] : []),
    ]
  },
  {
    label: '个人事务', items: [
      {path: '/applications', label: '我的申请', icon: 'clipboard'},
      {path: '/waitlists', label: '我的候补', icon: 'wait'},
      {path: '/notifications', label: '站内通知', icon: 'bell'},
    ]
  },
  ...(session.manager ? [{
    label: '管理中心', items: [
      {path: '/approvals', label: '预约审批', icon: 'approval'},
      {path: '/rules', label: '开放与占用管理', icon: 'sliders'},
      {path: '/imports', label: '资料导入', icon: 'upload'},
      ...(session.admin ? [
        {path: '/users', label: '账号与权限', icon: 'users'},
        {path: '/event-delivery', label: '消息投递管理', icon: 'delivery'},
      ] : []),
    ]
  }] : []),
])
</script>

<template>
  <div class="sidebar-content">
    <router-link to="/resources" class="brand" aria-label="校园资源预约首页" @click="$emit('navigate')">
      <span class="brand-mark"><AppIcon name="campus" :size="25"/></span>
      <span class="brand-copy">校园资源预约<small>Campus resources</small></span>
    </router-link>
    <nav aria-label="主导航" class="main-nav">
      <el-menu router :default-active="$route.path" @select="$emit('navigate')">
        <el-menu-item-group v-for="group in groups" :key="group.label" :title="group.label">
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"
                        :aria-current="$route.path === item.path ? 'page' : undefined">
            <AppIcon :name="item.icon"/>
            <span>{{ item.label }}</span>
            <span v-if="$route.path === item.path" class="nav-active-dot" aria-hidden="true"/>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </nav>
    <div class="sidebar-note">
      <span class="sidebar-note-icon"><AppIcon name="calendar" :size="18"/></span>
      <div><strong>从找到资源，到安心使用</strong>
        <p>查询时段 · 提交申请 · 等待审批</p></div>
    </div>
    <div class="sidebar-foot">校园资源预约平台<span>场地与设备协同管理</span></div>
  </div>
</template>
