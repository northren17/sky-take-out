import type { Store } from 'vuex'
import type { Router, RouteLocationNormalizedLoaded } from 'vue-router'
import type { IRootState } from './store'

declare module '@vue/runtime-core' {
  interface ComponentCustomProperties {
    $message: typeof import('element-plus')['ElMessage']
    $confirm: (...args: any[]) => Promise<any>
    $notify: typeof import('element-plus')['ElNotification']
    $store: Store<IRootState>
    $router: Router
    $route: RouteLocationNormalizedLoaded
    moment: typeof import('moment')
    $checkProcessEnv: () => boolean
    $echarts: typeof import('echarts')
  }
}

export {}
