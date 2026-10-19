import { createApp, type ObjectDirective } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/global.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

const imageErrorHandlers = new WeakMap<HTMLImageElement, EventListener>()
const imageFallback: ObjectDirective<HTMLImageElement> = {
  mounted(el) {
    const handler = () => {
      if (!el.src.endsWith('/static/imgDefault.png')) {
        el.src = '/static/imgDefault.png'
        el.classList.add('is-image-fallback')
      }
    }
    imageErrorHandlers.set(el, handler)
    el.addEventListener('error', handler)
  },
  beforeUnmount(el) {
    const handler = imageErrorHandlers.get(el)
    if (handler) el.removeEventListener('error', handler)
    imageErrorHandlers.delete(el)
  },
}

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.directive('image-fallback', imageFallback)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
