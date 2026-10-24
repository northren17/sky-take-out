import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表 —— 与小程序 pages.json 的 11 个页面一一对应
 * 小程序路径                  ->  本工程路由
 * pages/index/index           ->  /index
 * pages/order/index           ->  /order
 * pages/details/index         ->  /order/detail/:orderId
 * pages/pay/index             ->  /pay/:orderId
 * pages/success/index         ->  /success/:orderId
 * pages/remark/index          ->  /remark
 * pages/address/address       ->  /address
 * pages/addOrEditAddress/...  ->  /address/edit
 * pages/historyOrder/...      ->  /history
 * pages/my/my                 ->  /my
 * pages/nonet/index           ->  /nonet
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/index' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true, layout: 'none' },
  },
  {
    path: '/nonet',
    name: 'nonet',
    component: () => import('@/views/nonet/index.vue'),
    meta: { title: '无网络', public: true, layout: 'none' },
  },
  {
    path: '/index',
    name: 'index',
    component: () => import('@/views/index/index.vue'),
    meta: { title: '智享云膳' },
  },
  {
    path: '/order',
    name: 'order',
    component: () => import('@/views/order/index.vue'),
    meta: { title: '提交订单' },
  },
  {
    path: '/order/detail/:orderId',
    name: 'order-detail',
    component: () => import('@/views/order/detail.vue'),
    meta: { title: '订单详情' },
  },
  {
    path: '/history',
    name: 'history',
    component: () => import('@/views/order/history.vue'),
    meta: { title: '历史订单' },
  },
  {
    path: '/pay/:orderId',
    name: 'pay',
    component: () => import('@/views/pay/index.vue'),
    meta: { title: '支付订单' },
  },
  {
    path: '/success/:orderId',
    name: 'success',
    component: () => import('@/views/success/index.vue'),
    meta: { title: '提交成功' },
  },
  {
    path: '/remark',
    name: 'remark',
    component: () => import('@/views/remark/index.vue'),
    meta: { title: '订单备注' },
  },
  {
    path: '/address',
    name: 'address',
    component: () => import('@/views/address/index.vue'),
    meta: { title: '地址管理' },
  },
  {
    path: '/address/edit',
    name: 'address-edit',
    component: () => import('@/views/address/edit.vue'),
    meta: { title: '新增收货地址' },
  },
  {
    path: '/my',
    name: 'my',
    component: () => import('@/views/my/index.vue'),
    meta: { title: '个人中心' },
  },
  {
    path: '/dining-preferences',
    name: 'dining-preferences',
    component: () => import('@/views/dining-preferences/index.vue'),
    meta: { title: '口味与每日预算' },
  },
  {
    path: '/coupons',
    name: 'coupons',
    component: () => import('@/views/coupon/index.vue'),
    meta: { title: '优惠券' },
  },
  {
    path: '/meals',
    name: 'meals',
    component: () => import('@/views/meal/index.vue'),
    meta: { title: '套餐专区' },
  },
  {
    path: '/reservation',
    name: 'reservation',
    component: () => import('@/views/reservation/index.vue'),
    meta: { title: '预订点餐' },
  },
  {
    path: '/reviews',
    name: 'reviews',
    component: () => import('@/views/review/index.vue'),
    meta: { title: '评价晒单' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/index' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: (to) => {
    if (to.hash) return { el: to.hash, top: 104, behavior: 'smooth' }
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  const user = useUserStore()
  if (to.meta.public) return true
  if (!user.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  if (typeof title === 'string' && title) {
    document.title = title
  }
})

export default router
