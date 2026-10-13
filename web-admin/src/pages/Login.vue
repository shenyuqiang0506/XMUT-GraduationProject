<script setup lang="ts">
/**
 * 本地账号登录页面，只暂存用户当次输入的密码。
 * 成功获取令牌及当前身份后进入目录，成功登录后清空密码输入；错误通过统一提示展示。
 */
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {useSession} from '../stores/session'
import {explain} from '../api/client'

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
      <div>校园资源预约</div>
      <h1>让校园资源<br>清晰可见</h1>
      <p>统一管理场地与设备资料，保留每一份来源。<br>以明确的账号与授权，完成日常管理。</p></section>
    <section class="login-card">
      <el-form @submit.prevent="submit"><h1>登录工作台</h1>
        <p class="lead">学生使用学号，教师使用工号；管理员使用分配的账号登录。</p>
        <el-form-item label="账号" label-position="top">
          <el-input v-model="username" name="username" autocomplete="username" maxlength="64"/>
        </el-form-item>
        <el-form-item label="密码" label-position="top">
          <el-input v-model="password" name="password" type="password" show-password autocomplete="current-password"
                    maxlength="72"/>
        </el-form-item>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <el-button type="primary" native-type="submit" :loading="busy">登录</el-button>
        <p class="small">凭据仅保留在当前页面会话中；关闭或刷新页面后需重新登录。</p></el-form>
    </section>
  </div>
</template>
