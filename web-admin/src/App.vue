<script setup lang="ts">
/**
 * 登录后的公共布局、角色菜单和退出入口。
 * 失去会话时跳转登录页；各页面业务状态由 router-view 对应组件管理。
 */
import {watch} from 'vue'
import {useRouter} from 'vue-router'
import {useSession} from './stores/session'

const session = useSession(), router = useRouter()
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
  <router-view v-if="!session.signedIn"/>
  <div v-else class="shell">
    <aside>
      <div class="brand"><b>校</b><span>校园资源预约</span></div>
      <p class="small">场地与设备 · 管理工作台</p>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/resources">资源目录</el-menu-item>
        <el-menu-item index="/availability">可用时段查询</el-menu-item>
        <el-menu-item v-if="session.manager" index="/rules">开放与占用管理</el-menu-item>
        <el-menu-item v-if="session.manager" index="/imports">资料导入</el-menu-item>
        <el-menu-item v-if="session.admin" index="/users">账号与权限</el-menu-item>
      </el-menu>
      <div class="stage">当前阶段 M3<br>可用性基础 · 预约审批待开发</div>
    </aside>
    <section class="workspace">
      <header><span>校园资源预约</span>
        <div>{{ session.actor?.displayName }}
          <el-button text @click="logout">退出登录</el-button>
        </div>
      </header>
      <main>
        <router-view/>
      </main>
    </section>
  </div>
</template>
