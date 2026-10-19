/**
 * 微信相关能力的降级封装
 *
 * 背景（源码事实）：
 * 小程序唯一的登录方式是 `uni.login()` 微信授权拿 `code`，再
 * `POST /user/user/login { code }` 换 `data.token`。全套接口都以 `authentication` 头
 * 携带这个 token（`utils/request.js`）。
 *
 * 但 `wx.login` / `uni.login` 是**微信小程序 / 公众号容器专有 API**，普通浏览器里不存在。
 * 因此在网页版里：
 *  - 若运行在微信内置浏览器且页面接入了微信网页授权（`snsapi_base`），
 *    我们会从 URL 上拿到 `code`，此时**完整走小程序的登录链路**；
 *  - 否则退化为"手工填 token"，不做任何伪造登录或 mock。
 *
 * ⚠️ 重要：网页授权要求后端配置微信 AppID/AppSecret 并把 `redirect_uri` 指向本页面。
 *    仓库里没有任何此类配置，因此该能力默认不会生效 —— 这一点已在交付说明中标注。
 */

/** 微信内置浏览器 */
export function isWeChatBrowser(): boolean {
  return /micromessenger/i.test(navigator.userAgent)
}

/** 从当前地址上取微信网页授权回调带来的 code */
export function readWeChatCodeFromUrl(): string {
  const search = new URLSearchParams(window.location.search)
  // 微信授权回调用 query 传 code；hash 路由下也可能挂在 hash 的 query 上
  const fromSearch = search.get('code')
  if (fromSearch) return fromSearch

  const hash = window.location.hash
  const qIndex = hash.indexOf('?')
  if (qIndex >= 0) {
    const fromHash = new URLSearchParams(hash.slice(qIndex + 1)).get('code')
    if (fromHash) return fromHash
  }
  return ''
}

/**
 * 尝试用微信 code 登录。
 * @returns 成功返回 token；不可用（非微信环境 / 无 code）返回空字符串
 */
export function tryGetWeChatToken(): string {
  if (!isWeChatBrowser()) return ''
  return readWeChatCodeFromUrl()
}

/** 清理地址栏上的 code，避免刷新时重复使用 */
export function clearWeChatCodeFromUrl() {
  const url = new URL(window.location.href)
  if (!url.searchParams.has('code')) return
  url.searchParams.delete('code')
  url.searchParams.delete('state')
  window.history.replaceState({}, '', url.toString())
}
