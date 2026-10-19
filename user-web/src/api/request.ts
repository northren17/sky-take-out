import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { SUCCESS_CODE } from '@/constants/business'

/**
 * 请求层 —— 对齐小程序 v2.0 的 `utils/request.js`
 *
 * 原实现的关键行为（逐条对齐，不擅自更改语义）：
 *  1. 请求头 `authentication: <token>`（**全小写**，不是 Authorization）。
 *  2. 成功判定 `data.code == 200 || data.code === 1`。
 *  3. resolve 的是**整个响应体**（即 `R<T>` 包装，含 code/msg/data），**不剥到 data 层**，
 *     所以调用方要写 `res.data`、`if (res.code === 1)`。
 *  4. 非成功码走 reject。
 *
 * 相对原实现的两处**有意改进**（已在计划中说明）：
 *  - 原实现的 reject 在页面里被 `.catch(err => {})` 空吞，出错时用户看不到任何提示；
 *    这里统一弹一次 `ElMessage`，同时仍把错误抛给调用方，调用方需要静默时可自行 catch。
 *  - HTTP 401 / 403 视为登录失效：清空 token 并跳转登录页。
 */

const SUCCESS_CODES = new Set([200, SUCCESS_CODE])

/** 原小程序对同一次失败会重复弹提示，这里做一次去重节流 */
let lastMessage = ''
let lastMessageAt = 0
const MESSAGE_THROTTLE_MS = 1500

function toastError(message: string) {
  if (!message) return
  const now = Date.now()
  if (message === lastMessage && now - lastMessageAt < MESSAGE_THROTTLE_MS) return
  lastMessage = message
  lastMessageAt = now
  ElMessage({ message, type: 'error', duration: 2000 })
}

/** 后端返回体结构：`{ code, msg?, data? }` */
export interface ApiEnvelope<T = unknown> {
  code: number
  msg?: string | null
  data?: T
}

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 注意：token 取自 Pinia；在 app.use(createPinia()) 之后才可用
  const user = useUserStore()
  config.headers.set('Accept', 'application/json')
  config.headers.set('authentication', user.token ?? '')
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data

    // 兼容非 JSON 响应（例如导出类接口直接返回文本）
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return response
    }

    const envelope = body as ApiEnvelope
    if (SUCCESS_CODES.has(Number(envelope.code))) {
      return response
    }

    // 业务失败：code 非成功值
    if (Number(envelope.code) === 401 || Number(envelope.code) === 403) {
      handleUnauthorized()
    }
    const message = String(envelope.msg ?? '') || '请求失败'
    toastError(message)
    return Promise.reject(envelope)
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401 || status === 403) {
      handleUnauthorized()
    }
    const message =
      error?.response?.data?.msg ??
      (status ? `请求失败（HTTP ${status}）` : error?.message || '网络异常，请检查后端服务是否已启动')
    toastError(String(message))
    return Promise.reject(error)
  },
)

function handleUnauthorized() {
  const user = useUserStore()
  if (!user.token) return
  // 用 clearSession 而不是 logout：此处令牌已失效，再调 /logout 只会又拿到一个 401
  user.clearSession()
  toastError('登录已失效，请重新登录')
  // 用 hash 路由，避免依赖 router 实例造成循环引用
  const redirect = encodeURIComponent(window.location.hash.replace(/^#/, '') || '/index')
  window.location.hash = `#/login?redirect=${redirect}`
}

/* -------------------------------------------------------------------------- */
/* 对外 API                                                                    */
/* -------------------------------------------------------------------------- */

/**
 * 发起请求，返回**整个响应体**（`R<T>` 包装）。
 * 泛型参数是你对响应体的断言；推荐直接用 `src/types/api.d.ts` 里的别名：
 *   `http.get<ApiResult<'get_user_dish_list'>>('/user/dish/list', { params })`
 */
export const request = {
  get<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return http.get<unknown, AxiosResponse<T>>(url, config).then((res) => res.data)
  },
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return http.post<unknown, AxiosResponse<T>>(url, data, config).then((res) => res.data)
  },
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return http.put<unknown, AxiosResponse<T>>(url, data, config).then((res) => res.data)
  },
  delete<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return http.delete<unknown, AxiosResponse<T>>(url, config).then((res) => res.data)
  },
}

/**
 * 图片地址拼装 —— 对齐源码 `getNewImage()`：
 *   `${baseUrl}/common/download?name=${image}`
 * 注意源码里 api.js 另有一个未被调用的 `/user/common/download`，两者前缀不一致，
 * 因此这里用 `.env` 的 `VITE_IMAGE_PREFIX` 配置，默认值与源码真正在用的写法一致。
 */
export function getImageUrl(image?: string | null): string {
  if (!image) return ''
  // 已经是完整地址或 dataURL 时直接返回
  if (/^(https?:)?\/\//i.test(image) || image.startsWith('data:')) return image
  const prefix = import.meta.env.VITE_IMAGE_PREFIX || '/api/common/download?name='
  return `${prefix}${encodeURIComponent(image)}`
}

/** 店铺电话：源码里调用 `/user/shop/phone` 后把值硬编码为常量，此处同样以配置为准 */
export const SHOP_PHONE = import.meta.env.VITE_SHOP_PHONE || '400-618-4000'

/** 店铺显示名 */
export const SHOP_NAME = import.meta.env.VITE_SHOP_NAME || '苍穹外卖'

/** 配送费（原小程序多处硬编码为 6） */
export const DELIVERY_FEE = 6

export default http
