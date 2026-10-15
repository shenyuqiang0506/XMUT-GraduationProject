import {ref, shallowRef, computed} from 'vue'
import type {Page} from '../types'

/**
 * 下拉选项顺序分页：请求成功后才递增页码，失败重试仍请求原页。
 * 同时只发一个请求；按 ID 合并，防止目录变动造成相邻页出现重复记录。
 */
export function usePagedOptions<T extends { id: string }>(fetchPage: (page: number) => Promise<Page<T>>) {
    const items = shallowRef<T[]>([]), total = ref(0), page = ref(0), loading = ref(false), exhausted = ref(false)
    const hasMore = computed(() => !exhausted.value)

    async function more() {
        if (loading.value || !hasMore.value) return
        loading.value = true
        try {
            const next = page.value + 1, data = await fetchPage(next)
            const merged = new Map(items.value.map(item => [item.id, item]))
            data.items.forEach(item => merged.set(item.id, item))
            items.value = [...merged.values()]
            total.value = data.total
            exhausted.value = data.items.length === 0 || next * data.size >= data.total
            page.value = next
        } finally {
            loading.value = false
        }
    }

    return {items, total, loading, hasMore, more}
}
