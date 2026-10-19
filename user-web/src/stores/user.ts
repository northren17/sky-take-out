import { defineStore } from 'pinia'
import { ref } from 'vue'
import { userLogout } from '@/api/modules/user'

const TOKEN_KEY = 'sky_token'
const USER_KEY = 'sky_base_user_info'
const ACCOUNT_KEY = 'sky_account'

/** 与原小程序 `baseUserInfo`（uni.getUserProfile 结果）结构一致 */
export interface BaseUserInfo {
  nickName?: string
  avatarUrl?: string
  gender?: number | string
  [key: string]: unknown
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

/**
 * 用户会话
 * 原小程序只把 token 放在 Vuex 内存里（不落盘，刷新即丢）；网页版持久化到 localStorage 并做降级。
 * 网页版账号密码登录后，额外记录登录账号，用于个人中心展示。
 */
export const useUserStore = defineStore('user', () => {
  /** 对应小程序 Vuex state.token，用于 `authentication` 请求头 */
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  /** 对应小程序 Vuex state.baseUserInfo */
  const baseUserInfo = ref<BaseUserInfo>(readJson<BaseUserInfo>(USER_KEY, {}))
  /** 网页版登录账号（微信登录时为空） */
  const account = ref<string>(localStorage.getItem(ACCOUNT_KEY) ?? '')

  function setToken(value: string) {
    token.value = value
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  }

  function setBaseUserInfo(value: BaseUserInfo) {
    baseUserInfo.value = value ?? {}
    localStorage.setItem(USER_KEY, JSON.stringify(baseUserInfo.value))
  }

  function setAccount(value: string) {
    account.value = value ?? ''
    if (value) localStorage.setItem(ACCOUNT_KEY, value)
    else localStorage.removeItem(ACCOUNT_KEY)
  }

  /** 仅清空本地会话（请求层 401 时使用，不能再发请求） */
  function clearSession() {
    setToken('')
    setAccount('')
    baseUserInfo.value = {}
    localStorage.removeItem(USER_KEY)
  }

  /**
   * 主动退出登录：先通知服务端注销 Redis 中的令牌，再清空本地会话。
   * 服务端调用失败（例如断网）也要保证本地退出，因此用 finally。
   */
  async function logout(): Promise<boolean> {
    let serverRevoked = false
    try {
      if (token.value) {
        await userLogout()
        serverRevoked = true
      }
    } catch {
      serverRevoked = false
    } finally {
      clearSession()
    }
    return serverRevoked
  }

  return { token, baseUserInfo, account, setToken, setBaseUserInfo, setAccount, clearSession, logout }
})
