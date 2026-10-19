/**
 * 无头浏览器渲染自检（CDP），不参与构建。
 *
 * 它做四件事：
 *  1. 自己拉起 `vite preview` 托管 `dist/`（必须是先 `pnpm build` 过的产物），
 *     这样不依赖外部常驻的 dev server；
 *  2. 在页面里写入一个假 token 到 localStorage，越过登录守卫；
 *  3. 用 hash 跳转逐个导航（不用 Page.navigate，避免整页重载冲掉 token）；
 *  4. 收集每页的 `Runtime.exceptionThrown` / `console.error`，并抓取渲染后的可见文本。
 *
 * 注意：**这是渲染自检，不是接口自检**。后端 localhost:8080 未启动，
 * 所有 API 请求都会失败（页面显示空态），这属于预期；本脚本只关心
 * "组件能否挂载、模板能否渲染、有没有 JS 报错"。
 *
 * 用法：node scripts/check-render.mjs [baseUrl]
 *   不带参数：自建 preview 服务（默认 http://localhost:4173）
 *   带参数：  使用你已有的服务地址
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')

const PREVIEW_PORT = 4173
const CDP_PORT = 9333
const MOCK_PORT = Number(process.env.SKY_MOCK_PORT ?? 18080)
const externalBase = process.argv[2]
const BASE = externalBase ?? `http://localhost:${PREVIEW_PORT}`
const PORT = CDP_PORT

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
]

const browserPath = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!browserPath) {
  console.error('找不到 Chrome/Edge，跳过渲染自检')
  process.exit(2)
}

/** 路由 -> 期望出现的文案片段（部分依赖桩服务返回的数据） */
const ROUTES = [
  { hash: '#/login', expect: ['苍穹外卖', '账号', '密码', '验证码', '注册'] },
  { hash: '#/nonet', expect: ['网络无法连接', '点击刷新'] },
  // 首页：店铺信息 + 分类 + 菜品数据（来自桩服务）
  { hash: '#/index', expect: ['个人中心', '苍穹外卖', '营业中', '热销推荐', '宫保鸡丁', '去结算', '语音助手'] },
  // 提交订单：地址回填 + 购物车明细 + 费用
  { hash: '#/order', expect: ['提交订单', '张三', '宫保鸡丁', '配送费', '去支付'] },
  { hash: '#/remark', expect: ['订单备注', '完成'] },
  { hash: '#/address', expect: ['地址管理', '朝阳区', '新增收货地址'] },
  { hash: '#/address/edit', expect: ['新增收货地址', '联系人', '保存地址'] },
  { hash: '#/history', expect: ['历史订单', '全部订单', '待付款', '退款'] },
  { hash: '#/order/detail/9001', expect: ['订单详情', '宫保鸡丁', '联系商家'] },
  { hash: '#/pay/1', expect: ['支付订单', '微信支付', '确认支付'] },
  { hash: '#/success/1', expect: ['下单成功', '返回首页', '查看订单'] },
  { hash: '#/my', expect: ['地址管理', '历史订单'] },
]

const profileDir = mkdtempSync(join(tmpdir(), 'sky-render-'))

/** 自建预览服务 + 后端桩服务（仅当没有传入外部地址时） */
let preview = null
let mock = null
if (!externalBase) {
  if (!existsSync(join(root, 'dist', 'index.html'))) {
    console.error('dist/index.html 不存在，请先执行 pnpm build')
    process.exit(2)
  }
  const node = process.execPath
  const viteBin = join(root, 'node_modules', 'vite', 'bin', 'vite.js')
  // 后端桩：让 /api 代理有目标，从而把"接口 -> 页面"链路也验证掉
  mock = spawn(node, [join(here, 'mock-backend.mjs'), String(MOCK_PORT)], {
    cwd: root,
    stdio: 'ignore',
  })
  preview = spawn(
    node,
    [viteBin, 'preview', '--port', String(PREVIEW_PORT), '--strictPort'],
    {
      cwd: root,
      stdio: 'ignore',
      env: { ...process.env, VITE_PROXY_TARGET: `http://127.0.0.1:${MOCK_PORT}` },
    },
  )
}

const child = spawn(
  browserPath,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-allow-origins=*',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profileDir}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

function cleanup(code) {
  try {
    child.kill()
  } catch {
    /* ignore */
  }
  if (preview) {
    try {
      preview.kill()
    } catch {
      /* ignore */
    }
  }
  if (mock) {
    try {
      mock.kill()
    } catch {
      /* ignore */
    }
  }
  setTimeout(() => {
    try {
      rmSync(profileDir, { recursive: true, force: true })
    } catch {
      /* ignore */
    }
    process.exit(code)
  }, 300)
}

/** 等 HTTP 服务可用 */
async function waitForHttp(url, label) {
  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(url)
      if (res.ok) return
    } catch {
      /* retry */
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error(`${label} 未就绪：${url}`)
}

async function waitForDevtools() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (res.ok) return await res.json()
    } catch {
      /* retry */
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error('DevTools 端点未就绪')
}

/** 极简 CDP 客户端（Node 22+ 自带 WebSocket） */
class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url)
    this.id = 0
    this.pending = new Map()
    this.handlers = new Map()
    this.ready = new Promise((resolve, reject) => {
      this.ws.addEventListener('open', () => resolve())
      this.ws.addEventListener('error', (e) => reject(e))
    })
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(JSON.stringify(msg.error)))
        else resolve(msg.result)
        return
      }
      const list = this.handlers.get(msg.method)
      if (list) for (const h of list) h(msg.params)
    })
  }
  on(method, handler) {
    if (!this.handlers.has(method)) this.handlers.set(method, [])
    this.handlers.get(method).push(handler)
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }
  close() {
    try {
      this.ws.close()
    } catch {
      /* ignore */
    }
  }
}

async function main() {
  await waitForDevtools()
  // 等预览服务起来（外部地址由调用方保证）
  await waitForHttp(`${BASE}/`, 'HTTP 服务')
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
  const page = targets.find((t) => t.type === 'page')
  if (!page) throw new Error('没有可用的 page target')

  const cdp = new Cdp(page.webSocketDebuggerUrl)
  await cdp.ready
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Log.enable')

  const errors = []
  cdp.on('Runtime.exceptionThrown', (p) => {
    const d = p?.exceptionDetails
    errors.push(`[exception] ${d?.exception?.description ?? d?.text ?? 'unknown'}`)
  })
  cdp.on('Runtime.consoleAPICalled', (p) => {
    if (p?.type === 'error') {
      errors.push(`[console.error] ${(p.args ?? []).map((a) => a.value ?? a.description).join(' ')}`)
    }
  })
  cdp.on('Log.entryAdded', (p) => {
    if (p?.entry?.level === 'error') errors.push(`[log] ${p.entry.text}`)
  })

  // token 必须在**页面脚本执行之前**就写进 localStorage：
  // Pinia store 只在启动时读一次 localStorage，若等页面加载完再写，
  // 首屏路由守卫看到的 token 仍是空的，会被重定向到登录页（后续检查全部失败）。
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try {
      localStorage.setItem('sky_token', 'render-check-fake-token');
    } catch (e) {}`,
  })
  await cdp.send('Page.navigate', { url: `${BASE}/` })
  await new Promise((r) => setTimeout(r, 2500))
  const wrote = await cdp.send('Runtime.evaluate', {
    expression: `localStorage.getItem('sky_token')`,
    returnByValue: true,
  })
  console.log(`  · 已注入 token：${JSON.stringify(wrote?.result?.value)}`)

  // 验证守卫确实放行了（token 生效），否则后面全是登录页，等于没测
  await cdp.send('Runtime.evaluate', { expression: `(location.hash = '#/index', true)` })
  await new Promise((r) => setTimeout(r, 1200))
  const guardCheck = await cdp.send('Runtime.evaluate', {
    expression: `(() => { const a = document.querySelector('#app'); return (a ? a.innerText : '').slice(0, 60) })()`,
    returnByValue: true,
  })
  const homeText = String(guardCheck?.result?.value ?? '')
  // 登录页的特征文案：出现「验证码」且没有「点餐」说明被踢回登录页了
  if (homeText.includes('验证码') && !homeText.includes('点餐')) {
    console.log('  ! 守卫未放行，仍停留在登录页；后续页面检查会全部失败')
    console.log('    提示：后端存在时，失效 token 会被 401 清会话而跳回登录页；')
    console.log('          请确认后端未启动（走 mock-backend）或使用有效的 token。')
  } else {
    console.log('  · 守卫已放行，token 生效')
  }

  let failed = 0
  for (const route of ROUTES) {
    errors.length = 0
    // 关键：只用 hash 跳转，避免整页重载把 token 冲掉（这才是 SPA 的真实导航方式）
    await cdp.send('Runtime.evaluate', {
      expression: `(location.hash = ${JSON.stringify(route.hash)}, true)`,
    })
    await new Promise((r) => setTimeout(r, 900))

    const res = await cdp.send('Runtime.evaluate', {
      expression: `(() => { const a = document.querySelector('#app'); return a ? a.innerText : '' })()`,
      returnByValue: true,
    })
    const text = String(res?.result?.value ?? '').replace(/\s+/g, ' ')
    const missing = route.expect.filter((kw) => !text.includes(kw))

    if (missing.length === 0 && errors.length === 0) {
      console.log(`  ✓ ${route.hash.padEnd(22)} 渲染正常`)
    } else {
      failed += 1
      console.log(`  ✗ ${route.hash.padEnd(22)} ${missing.length ? `缺少文案: ${missing.join(' / ')}` : ''}`)
      for (const e of errors.slice(0, 4)) console.log(`      ${e}`)
      if (text) console.log(`      实际文本: ${text.slice(0, 160)}`)
    }
  }

  cdp.close()
  console.log(`\n==== 渲染自检：${failed === 0 ? '全部通过' : `${failed} 个页面异常`} ====\n`)
  cleanup(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('渲染自检异常：', e)
  cleanup(1)
})
