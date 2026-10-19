import { createApp } from 'vue'
import 'normalize.css'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import moment from 'moment'
import '@/styles/element-variables.scss'
import '@/styles/index.scss'
import '@/styles/home.scss'

import * as echarts from 'echarts'
// 瑞吉外卖样式表
import '@/styles/newRJWMsystem.scss'
import '@/styles/icon/iconfont.css'
import '@/styles/admin-theme.scss'
import App from '@/App.vue'
import SvgIcon from '@/icons/SvgIcon'
import store from '@/store'
import router from '@/router'
import '@/icons/components'
import '@/permission'
import { checkProcessEnv } from '@/utils/common'

const app = createApp(App)

app.use(router)
app.use(store)
app.use(ElementPlus)
app.component('ArrowDown', ArrowDown)
app.component('Search', Search)
app.component('svg-icon', SvgIcon)
app.config.globalProperties.moment = moment
app.config.globalProperties.$checkProcessEnv = checkProcessEnv
app.config.globalProperties.$echarts = echarts

app.mount('#app')
