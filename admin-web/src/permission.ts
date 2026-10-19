import router from './router'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import Cookies from 'js-cookie'

NProgress.configure({ 'showSpinner': false })

router.beforeEach((to) => {
  NProgress.start()
  if (Cookies.get('token') || to.meta.notNeedAuth) {
    return true
  }
  return '/login'
})

router.afterEach((to) => {
  NProgress.done()
  document.title = String(to.meta.title || '')
})
