// 兼容现有后端契约，所有业务页面统一使用一个模式。
// 自动化开发验收沿用隔离资料；生产构建固定使用业务资料，不提供用户切换入口。
export const DATA_MODE = import.meta.env.DEV && import.meta.env.VITE_TEST_DATA_MODE === 'DEMO' ? 'DEMO' : 'REAL'
