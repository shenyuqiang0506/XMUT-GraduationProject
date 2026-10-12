<script setup lang="ts">
/**
 * 系统管理员账号管理页面：创建账号、调整角色、启用或停用。
 * 修改请求携带身份版本与幂等键；后端拒绝最后管理员被停用或降权。
 */
import {ref, onMounted} from 'vue'
import {ElMessage} from 'element-plus'
import {api, explain, mutationKeys} from '../api/client'
import type {Page, User, Role} from '../types'

const rows = ref<User[]>([]), page = ref(1), total = ref(0), username = ref(''), busy = ref(false), error = ref(''),
    dialog = ref(false), saving = ref(false), keys = mutationKeys(), roleDialog = ref(false),
    target = ref<User | null>(null), roles = ref<Role[]>([])
const form = ref({username: '', displayName: '', initialPassword: '', roleCodes: ['STUDENT'] as Role[]}),
    names: Record<Role, string> = {SYSTEM_ADMIN: '系统管理员', RESOURCE_ADMIN: '资源管理员', STUDENT: '学生'}

// 按当前页和账号筛选重新读取列表；失败保留错误提示，并在 finally 结束加载状态。
async function load() {
  busy.value = true;
  error.value = '';
  try {
    const data = await api<Page<User>>('/users?' + new URLSearchParams({
      page: String(page.value),
      size: '20',
      username: username.value
    }));
    rows.value = data.items;
    total.value = data.total
  } catch (e) {
    error.value = explain(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)

// 角色先排序，确保相同集合产生稳定请求内容；成功后清理初始密码并重新查询列表。
async function create() {
  saving.value = true;
  const payload = {...form.value, roleCodes: [...form.value.roleCodes].sort()};
  try {
    await api('/users', 'POST', payload, keys.key('createUser', payload));
    keys.done('createUser', payload);
    form.value.initialPassword = '';
    dialog.value = false;
    ElMessage.success('账号已创建');
    await load()
  } catch (e) {
    ElMessage.error(explain(e))
  } finally {
    saving.value = false
  }
}

// 每次创建都生成全新表单，避免沿用上次账号、密码或角色选择。
function open() {
  form.value = {username: '', displayName: '', initialPassword: '', roleCodes: ['STUDENT']};
  dialog.value = true
}

// 复制角色集合到编辑状态，确认前不直接修改列表中的服务器快照。
function editRoles(row: User) {
  target.value = row;
  roles.value = [...row.roles];
  roleDialog.value = true
}

// 带读取时的 authVersion 提交；并发变更会由后端拒绝，成功后旧会话立即失效。
async function changeRoles() {
  if (!target.value) return;
  const payload = {roleCodes: [...roles.value].sort(), expectedVersion: target.value.authVersion},
      path = '/users/' + target.value.id + '/roles';
  saving.value = true;
  try {
    await api(path, 'PUT', payload, keys.key(path, payload));
    keys.done(path, payload);
    roleDialog.value = false;
    ElMessage.success('权限已更新，原会话已撤销');
    await load()
  } catch (e) {
    ElMessage.error(explain(e))
  } finally {
    saving.value = false
  }
}

// 提交启停目标状态及预期版本，不在前端猜测最后管理员约束是否允许。
async function changeStatus(row: User) {
  const payload = {status: row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE', expectedVersion: row.authVersion},
      path = '/users/' + row.id + '/status';
  try {
    await api(path, 'PATCH', payload, keys.key(path, payload));
    keys.done(path, payload);
    ElMessage.success('账号状态已更新');
    await load()
  } catch (e) {
    ElMessage.error(explain(e))
  }
}
</script>
<template><h1>账号与权限</h1>
  <p class="lead">创建本地账号，管理角色与启停状态。权限变更或停用会立即撤销原会话。</p>
  <div class="card">
    <div class="toolbar">
      <el-input v-model="username" placeholder="筛选账号" clearable/>
      <el-button @click="page=1;load()">查询</el-button>
      <el-button type="primary" class="right" @click="open">创建账号</el-button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <el-table :data="rows" v-loading="busy">
      <el-table-column prop="username" label="账号" min-width="170"/>
      <el-table-column prop="displayName" label="姓名" min-width="120"/>
      <el-table-column label="角色" min-width="240">
        <template #default="{row}">
          <el-tag v-for="role in row.roles as Role[]" :key="role">{{ names[role] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{row}">
          <el-tag :type="row.status==='ACTIVE'?'success':'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{row}">
          <el-button link type="primary" @click="editRoles(row)">修改角色</el-button>
          <el-button link :type="row.status==='ACTIVE'?'danger':'primary'" @click="changeStatus(row)">
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next"
                   @current-change="load"/>
  </div>
  <el-dialog v-model="dialog" title="创建账号" width="500px" @closed="form.initialPassword=''">
    <el-form label-position="top" @submit.prevent="create">
      <el-form-item label="账号">
        <el-input v-model="form.username" maxlength="64" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="姓名">
        <el-input v-model="form.displayName" maxlength="100"/>
      </el-form-item>
      <el-form-item label="初始密码（12–72 字节）">
        <el-input v-model="form.initialPassword" type="password" show-password autocomplete="new-password"/>
      </el-form-item>
      <el-form-item label="角色">
        <el-checkbox-group v-model="form.roleCodes">
          <el-checkbox v-for="(name,role) in names" :key="role" :value="role">{{ name }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving">创建账号</el-button>
    </el-form>
  </el-dialog>
  <el-dialog v-model="roleDialog" title="修改角色" width="500px">
    <el-checkbox-group v-model="roles">
      <el-checkbox v-for="(name,role) in names" :key="role" :value="role">{{ name }}</el-checkbox>
    </el-checkbox-group>
    <p class="small">更新后此账号需重新登录；至少保留一位启用的系统管理员。</p>
    <el-button type="primary" :loading="saving" @click="changeRoles">保存角色</el-button>
  </el-dialog>
</template>
