/**
 * 截图脚本（仅用于开发期人工核对视觉，不参与构建）
 *
 * 自己拉起「后端桩服务 + vite preview」，写入 token 越过登录守卫，
 * 然后对若干路由截图到 `.reverse-engineering/screenshots/`。
 *
 * 用法：node scripts/screenshot.mjs
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const VIEWPORT_WIDTH = Number(process.env.SKY_SHOT_WIDTH ?? 420)
const VIEWPORT_HEIGHT = Number(process.env.SKY_SHOT_HEIGHT ?? 900)
const REAL_TOKEN = process.env.SKY_USER_TOKEN ?? ''
const REAL_ORDER_ID = process.env.SKY_ORDER_ID ?? ''
const outDir = join(
  root,
  '.reverse-engineering',
  `screenshots-${VIEWPORT_WIDTH}x${VIEWPORT_HEIGHT}${REAL_TOKEN ? '-real' : ''}`,
)
const PREVIEW_PORT = 4175
const CDP_PORT = 9335
const MOCK_PORT = Number(process.env.SKY_MOCK_PORT ?? 18080)
const BASE = `http://localhost:${PREVIEW_PORT}`

const SHOTS = [
  { name: '01-login', hash: '#/login', auth: false },
  { name: '02-index', hash: '#/index', auth: true },
  { name: '03-order', hash: '#/order', auth: true },
  { name: '04-history', hash: '#/history', auth: true },
  ...(!REAL_TOKEN || REAL_ORDER_ID
    ? [{ name: '05-order-detail', hash: `#/order/detail/${REAL_ORDER_ID || '9001'}`, auth: true }]
    : []),
  { name: '06-address', hash: '#/address', auth: true },
  { name: '07-remark', hash: '#/remark', auth: true },
  { name: '08-my', hash: '#/my', auth: true },
  { name: '09-success', hash: '#/success/1', auth: true },
]

const CHROME = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
].find((p) => existsSync(p))
if (!CHROME) {
  console.error('找不到 Chrome/Edge')
  process.exit(2)
}
if (!existsSync(join(root, 'dist', 'index.html'))) {
  console.error('dist 不存在，请先 pnpm build')
  process.exit(2)
}

mkdirSync(outDir, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const mock = REAL_TOKEN
  ? null
  : spawn(process.execPath, [join(here, 'mock-backend.mjs'), String(MOCK_PORT)], {
      cwd: root,
      stdio: 'ignore',
    })
const preview = spawn(
  process.execPath,
  [join(root, 'node_modules', 'vite', 'bin', 'vite.js'), 'preview', '--port', String(PREVIEW_PORT), '--strictPort'],
  {
    cwd: root,
    stdio: 'ignore',
    env: {
      ...process.env,
      VITE_PROXY_TARGET: REAL_TOKEN
        ? 'http://127.0.0.1:8080'
        : `http://127.0.0.1:${MOCK_PORT}`,
    },
  },
)
const profile = mkdtempSync(join(tmpdir(), 'sky-shot-'))
const chrome = spawn(
  CHROME,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--hide-scrollbars',
    '--remote-allow-origins=*',
    `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${profile}`,
    `--window-size=${VIEWPORT_WIDTH},${VIEWPORT_HEIGHT}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

function cleanup(code) {
  for (const p of [chrome, preview, mock].filter(Boolean)) {
    try {
      p.kill()
    } catch {
      /* ignore */
    }
  }
  setTimeout(() => {
    try {
      rmSync(profile, { recursive: true, force: true })
    } catch {
      /* ignore */
    }
    process.exit(code)
  }, 300)
}

async function waitHttp(url, tries = 100) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url)
      if (r.ok) return
    } catch {
      /* retry */
    }
    await sleep(250)
  }
  throw new Error(`服务未就绪：${url}`)
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url)
    this.id = 0
    this.pending = new Map()
    this.ready = new Promise((res, rej) => {
      this.ws.addEventListener('open', () => res())
      this.ws.addEventListener('error', (e) => rej(e))
    })
    this.ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data)
      if (m.id && this.pending.has(m.id)) {
        const { res, rej } = this.pending.get(m.id)
        this.pending.delete(m.id)
        if (m.error) rej(new Error(JSON.stringify(m.error)))
        else res(m.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((res, rej) => {
      this.pending.set(id, { res, rej })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }
}

async function main() {
  await waitHttp(`${BASE}/`)
  let list = null
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)
      if (r.ok) {
        list = await r.json()
        break
      }
    } catch {
      /* retry */
    }
    await sleep(250)
  }
  if (!list) throw new Error('DevTools 未就绪')

  const page = list.find((t) => t.type === 'page')
  const cdp = new Cdp(page.webSocketDebuggerUrl)
  await cdp.ready
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: VIEWPORT_WIDTH,
    height: VIEWPORT_HEIGHT,
    deviceScaleFactor: VIEWPORT_WIDTH <= 480 ? 2 : 1,
    mobile: VIEWPORT_WIDTH <= 480,
  })

  await cdp.send('Page.navigate', { url: `${BASE}/` })
  await sleep(1500)
  await cdp.send('Runtime.evaluate', {
    expression: `localStorage.setItem('sky_token', ${JSON.stringify(REAL_TOKEN || 'screenshot-token')})`,
  })
  await cdp.send('Page.reload', { ignoreCache: true })
  await sleep(2500)

  for (const shot of SHOTS) {
    await cdp.send('Runtime.evaluate', { expression: `(location.hash = '${shot.hash}', true)` })
    await sleep(1400)
    const audit = await cdp.send('Runtime.evaluate', {
      expression: `JSON.stringify({
        viewport: innerWidth,
        scrollWidth: document.documentElement.scrollWidth,
        brokenImages: [...document.images]
          .filter((img) => img.complete && img.naturalWidth === 0 && getComputedStyle(img).display !== 'none' && img.getClientRects().length)
          .map((img) => img.currentSrc || img.src),
        messages: [...document.querySelectorAll('.el-message--error')].map((el) => el.textContent?.trim()).filter(Boolean)
      })`,
      returnByValue: true,
    })
    const auditInfo = JSON.parse(audit.result.value)
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
    const file = join(outDir, `${shot.name}.png`)
    writeFileSync(file, Buffer.from(data, 'base64'))
    const widthState = auditInfo.scrollWidth <= auditInfo.viewport + 1 ? 'OK' : 'OVERFLOW'
    console.log(
      `  ${widthState} ${shot.name}.png (${shot.hash}) ${auditInfo.viewport}/${auditInfo.scrollWidth} broken=${auditInfo.brokenImages.length} errors=${auditInfo.messages.length}`,
    )
  }

  console.log(`\n截图已保存到 ${outDir}`)
  cleanup(0)
}

main().catch((e) => {
  console.error('截图失败：', e)
  cleanup(1)
})
