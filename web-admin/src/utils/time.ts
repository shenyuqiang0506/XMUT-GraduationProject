/**
 * 日期控件保存不带时区的墙上时刻字符串，统一按上海 UTC+08:00 解释。
 * 不调用浏览器本地 Date 的 toISOString，避免海外浏览器把输入时刻偏移后提交。
 */
export function shanghaiInstant(value: string | null | undefined, label: string): string {
    if (!value) throw new Error('请选择' + label)
    if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) throw new Error(label + '格式不正确')
    const iso = value.replace(' ', 'T') + '+08:00', instant = new Date(iso)
    // Date 会把 2 月 30 日等输入归一化；回查 UTC 字段，拒绝不存在的日期。
    if (!Number.isFinite(instant.getTime())
        || new Date(instant.getTime() + 8 * 3600000).toISOString().slice(0, 19) !== value.replace(' ', 'T'))
        throw new Error(label + '不是有效日期或时间')
    return iso
}

export function shanghaiRange(start: string | null | undefined, end: string | null | undefined, singleDay = false) {
    const startAt = shanghaiInstant(start, '开始时间'), endAt = shanghaiInstant(end, '结束时间')
    if (Date.parse(endAt) <= Date.parse(startAt)) throw new Error('结束时间必须晚于开始时间')
    if (singleDay && startAt.slice(0, 10) !== endAt.slice(0, 10))
        throw new Error('查询起止时间必须在同一上海自然日')
    return {startAt, endAt}
}
