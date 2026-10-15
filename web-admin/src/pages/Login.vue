<script setup lang="ts">
/**
 * 本地账号登录页面，只暂存用户当次输入的密码。
 * 成功获取令牌及当前身份后进入目录，成功登录后清空密码输入；错误通过统一提示展示。
 */
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {useSession} from '../stores/session'
import {explain} from '../api/client'
import AppIcon from '../components/AppIcon.vue'
import CampusIllustration from '../components/CampusIllustration.vue'

const username = ref(''), password = ref(''), busy = ref(false), error = ref(''), session = useSession(),
    router = useRouter()

// 只做必要的空值提示；密码规则、限流和账号有效性由身份服务校验，busy 用于显示提交状态。
async function submit() {
  error.value = '';
  if (!username.value || !password.value) {
    error.value = '请输入账号和密码';
    return
  }
  busy.value = true;
  try {
    await session.login(username.value, password.value);
    password.value = '';
    await router.replace('/resources')
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="login-wrap">
    <section class="login-art">
      <div class="brand"><span class="brand-mark"><AppIcon name="campus" :size="25"/></span><span class="brand-copy">校园资源预约<small>Campus resources</small></span>
      </div>
      <div class="login-story">
        <span class="eyebrow">连接校园里的每一种可能</span>
        <h2>让每一处空间，<br>都有用武之地。</h2>
        <p>一间讨论室，一套活动设备。<br>从找到合适的资源开始，让校园计划如期发生。</p>
        <CampusIllustration/>
      </div>
      <div class="login-art-footer"><span>场地预约</span><i/><span>设备协同</span><i/><span>进度可查</span></div>
    </section>
    <section class="login-card" aria-labelledby="login-title">
      <el-form id="login-form" tabindex="-1" label-position="top" @submit.prevent="submit">
        <div class="login-mobile-brand"><span class="brand-mark"><AppIcon name="campus"
                                                                          :size="26"/></span><span>校园资源预约</span>
        </div>
        <span class="eyebrow">欢迎回到校园</span>
        <h1 id="login-title">登录工作台</h1>
        <p class="lead">学生使用学号，教师使用工号；管理员使用分配的账号登录。</p>
        <el-form-item label="账号" label-position="top">
          <el-input v-model="username" name="username" autocomplete="username" maxlength="64"
                    placeholder="请输入学号、工号或管理账号" :disabled="busy"/>
        </el-form-item>
        <el-form-item label="密码" label-position="top">
          <el-input v-model="password" name="password" type="password" show-password autocomplete="current-password"
                    maxlength="72" placeholder="请输入登录密码" :disabled="busy"/>
        </el-form-item>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <el-button type="primary" native-type="submit" :loading="busy"><span>登录</span>
          <AppIcon name="arrow" :size="18"/>
        </el-button>
        <p class="login-account-hint">
          <AppIcon name="info" :size="16"/>
          账号由学校分配，如需帮助请联系管理员。
        </p>
        <p class="small login-session-hint">凭据仅保留在当前页面会话中；关闭或刷新页面后需重新登录。</p>
      </el-form>
      <p class="login-copyright">校园资源预约平台 · 场地与设备，一处管理</p>
    </section>
  </div>
</template>
