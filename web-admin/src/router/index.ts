import {createRouter, createWebHistory} from 'vue-router'
import {useSession} from '../stores/session'
import Login from '../pages/Login.vue'
import Resources from '../pages/Resources.vue'
import Imports from '../pages/Imports.vue'
import Users from '../pages/Users.vue'
import Availability from '../pages/Availability.vue'
import Rules from '../pages/Rules.vue'

export const router = createRouter({
    history: createWebHistory(),
    routes: [{path: '/login', component: Login}, {path: '/', redirect: '/resources'}, {
        path: '/resources',
        component: Resources
    }, {path: '/availability', component: Availability}, {
        path: '/rules',
        component: Rules,
        meta: {manager: true}
    }, {path: '/imports', component: Imports, meta: {manager: true}}, {
        path: '/users',
        component: Users,
        meta: {admin: true}
    }, {path: '/:pathMatch(.*)*', redirect: '/resources'}]
})
router.beforeEach(to => {
    const s = useSession();
    if (!s.signedIn && to.path != '/login') return '/login';
    if (s.signedIn && to.path === '/login') return '/resources';
    if (to.meta.admin && !s.admin || to.meta.manager && !s.manager) return '/resources'
})
