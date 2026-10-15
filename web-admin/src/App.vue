<script setup lang="ts">
/**
 * 登录后的公共布局、角色菜单和退出入口。
 * 失去会话时跳转登录页；各页面业务状态由 router-view 对应组件管理。
 */
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useSession} from './stores/session'
import AppNav from './components/AppNav.vue'
import AppIcon from './components/AppIcon.vue'

const session = useSession(), router = useRouter(), route = useRoute(), mobileNav = ref(false)
const pageNames: Record<string, string> = {
  '/resources': '资源目录', '/availability': '可用时段查询', '/ai': '智能资源查询',
  '/applications': '我的申请', '/waitlists': '我的候补', '/notifications': '站内通知',
  '/approvals': '预约审批', '/rules': '开放与占用管理', '/imports': '资料导入',
  '/users': '账号与权限', '/event-delivery': '消息投递管理',
}
const pageName = computed(() => pageNames[route.path] || '工作台')
const roleName = computed(() => session.admin ? '系统管理员' : session.manager ? '资源管理员'
    : session.actor?.roles.includes('TEACHER') ? '教师' : '学生')
const today = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
}).format(new Date())
watch(() => route.path, () => {
  mobileNav.value = false
})
const desktopViewport = window.matchMedia('(min-width: 961px)')

function closeMobileNav(event: MediaQueryListEvent) {
  if (event.matches) mobileNav.value = false
}

onMounted(() => desktopViewport.addEventListener('change', closeMobileNav))
onBeforeUnmount(() => desktopViewport.removeEventListener('change', closeMobileNav))
// 监听身份整体有效性，统一处理主动退出或接口请求发现会话失效后的页面跳转。
watch(() => session.signedIn, valid => {
  if (!valid) router.replace('/login')
})

async function logout() {
  await session.logout();
  await router.replace('/login')
}
</script>
<template>
  <a class="skip-link" :href="session.signedIn ? '#main-content' : '#login-form'">跳转到主要内容</a>
  <router-view v-if="!session.signedIn"/>
  <div v-else class="shell">
    <aside class="desktop-sidebar">
      <AppNav/>
    </aside>
    <el-drawer v-model="mobileNav" direction="ltr" size="272px" title="导航菜单" class="mobile-nav-drawer">
      <AppNav v-if="mobileNav" @navigate="mobileNav = false"/>
    </el-drawer>
    <section class="workspace">
      <header class="workspace-header">
        <div class="header-location">
          <button class="mobile-menu icon-button" aria-label="打开导航菜单" :aria-expanded="mobileNav"
                  @click="mobileNav = true">
            <AppIcon name="menu"/>
          </button>
          <span class="breadcrumb-root">工作台</span>
          <AppIcon name="chevron" :size="14"/>
          <span>{{ pageName }}</span>
        </div>
        <div class="header-account">
          <span class="header-date">{{ today }}</span>
          <span class="user-avatar">{{ session.actor?.displayName?.slice(0, 1) || '校' }}</span>
          <div class="user-identity"><strong :title="session.actor?.displayName">{{
              session.actor?.displayName
            }}</strong><span>{{ roleName }}</span></div>
          <el-button text class="logout-button" aria-label="退出登录" @click="logout">
            <AppIcon name="logout" :size="17"/>
            <span>退出登录</span></el-button>
        </div>
      </header>
      <main id="main-content" tabindex="-1">
        <el-alert v-if="session.refreshUncertain" type="warning" :closable="false"
                  title="刷新结果尚未确认，已暂停新请求。当前编辑内容已保留，请保留内容后重新登录。"/>
        <router-view/>
      </main>
      <footer class="workspace-footer">
        <span>校园资源预约平台</span></footer>
    </section>
  </div>
</template>
