/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 请求前缀，默认 /api（走 Vite 代理） */
  readonly VITE_API_BASE_URL: string
  /** 图片地址前缀 */
  readonly VITE_IMAGE_PREFIX: string
  /** 店铺显示名 */
  readonly VITE_SHOP_NAME: string
  /** 店铺联系电话 */
  readonly VITE_SHOP_PHONE: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
