<script setup lang="ts">
import {watch} from 'vue'
import {useRouter} from 'vue-router'
import {useSession} from './stores/session'

const session = useSession(), router = useRouter()
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
        <el-menu-item v-if="session.manager" index="/imports">资料导入</el-menu-item>
        <el-menu-item v-if="session.admin" index="/users">账号与权限</el-menu-item>
      </el-menu>
      <div class="stage">当前阶段 M2<br>目录管理与身份授权</div>
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
