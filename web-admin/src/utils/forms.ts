/** 用户填写的 JSON 必须是对象；null、数组和基本值不能作为设施、映射或来源说明。 */
export function jsonObject(text: string, label: string): Record<string, unknown> {
    let value: unknown
    try {
        value = JSON.parse(text)
    } catch {
        throw new Error(label + ' JSON 格式不正确，请检查引号、逗号和括号')
    }
    if (!value || typeof value !== 'object' || Array.isArray(value))
        throw new Error(label + '必须是 JSON 对象，例如 {}')
    return value as Record<string, unknown>
}

export function stringObject(text: string, label: string): Record<string, string> {
    const value = jsonObject(text, label)
    if (Object.values(value).some(item => typeof item !== 'string'))
        throw new Error(label + '的每个值都必须是字符串，请使用双引号')
    return value as Record<string, string>
}
