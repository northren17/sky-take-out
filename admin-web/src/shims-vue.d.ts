declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'is-promise'

declare module '@/config.json'

interface ImportMetaEnv {
  readonly BASE_URL: string
  readonly PROD: boolean
  readonly VITE_APP_BASE_API: string
  readonly VITE_APP_DELETE_PERMISSIONS: string
  readonly VITE_APP_SOCKET_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
