<script setup lang="ts">
import {computed} from 'vue'

/**
 * 上海业务时刻输入：使用不做时区转换的 datetime-local 字符串。
 * 原日期组件会按浏览器夏令时修正不存在的时刻，不能用于解释另一个时区的墙上时刻。
 * 这里只桥接 T 分隔符及省略的秒，UTC+08:00 转换与区间校验集中在 utils/time.ts。
 */
const model = defineModel<string>({default: ''})
const input = computed({
  get: () => model.value.replace(' ', 'T'),
  set: (value: string) => {
    const text = value.replace('T', ' ')
    model.value = text.length === 16 ? text + ':00' : text
  }
})
</script>

<template>
  <el-input v-model="input" type="datetime-local" step="1" autocomplete="off"/>
</template>
