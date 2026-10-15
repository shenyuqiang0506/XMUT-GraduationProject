<script setup lang="ts">
// 管理路由创建独立视图状态，离开个人列表时不沿用其分页、详情及请求结果。
import Applications from './Applications.vue'
</script>
<template>
  <Applications administrative/>
</template>
