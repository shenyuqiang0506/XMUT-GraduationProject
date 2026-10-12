/**
 * Web 管理端启动入口。
 * 先注册 Pinia 再注册路由，使导航守卫可读取会话；Element Plus 使用中文本地化。
 */
import {createApp} from 'vue'
import {createPinia} from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import {router} from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus, {locale: zhCn}).mount('#app')
