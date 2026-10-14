/**
 * 管理端路由与角色导航守卫。
 * meta 中的权限仅用于界面导航；真正的数据访问权限仍由后端每次请求重新检查。
 */
import {createRouter, createWebHistory} from 'vue-router'
import {useSession} from '../stores/session'
import Login from '../pages/Login.vue'
import Resources from '../pages/Resources.vue'
import Imports from '../pages/Imports.vue'
import Users from '../pages/Users.vue'
import Availability from '../pages/Availability.vue'
import Rules from '../pages/Rules.vue'
import Applications from '../pages/Applications.vue'
import Approvals from '../pages/Approvals.vue'
import Waitlists from '../pages/Waitlists.vue'
import Notifications from '../pages/Notifications.vue'
import EventDelivery from '../pages/EventDelivery.vue'
import IntelligentQuery from '../pages/IntelligentQuery.vue'

export const router = createRouter({
    history: createWebHistory(),
    routes: [{path: '/login', component: Login}, {path: '/', redirect: '/resources'}, {
        path: '/resources',
        component: Resources
    }, {path: '/availability', component: Availability}, {path:'/ai',component:IntelligentQuery,meta:{applicant:true}}, {path:'/applications',component:Applications},
        {path:'/waitlists',component:Waitlists}, {path:'/notifications',component:Notifications},
        {path:'/event-delivery',component:EventDelivery,meta:{admin:true}},
        {path:'/approvals',component:Approvals,meta:{manager:true}}, {
        path: '/rules',
        component: Rules,
        meta: {manager: true}
    }, {path: '/imports', component: Imports, meta: {manager: true}}, {
        path: '/users',
        component: Users,
        meta: {admin: true}
    }, {path: '/:pathMatch(.*)*', redirect: '/resources'}]
})
// 先检查登录状态，再检查管理员权限；不允许通过手工输入地址绕过页面导航限制。
router.beforeEach(to => {
    const s = useSession();
    if (!s.signedIn && to.path != '/login') return '/login';
    if (s.signedIn && to.path === '/login') return '/resources';
    if ((to.meta.admin && !s.admin) || (to.meta.manager && !s.manager)) return '/resources'
    if (to.meta.applicant && !s.applicant) return '/resources'
})
