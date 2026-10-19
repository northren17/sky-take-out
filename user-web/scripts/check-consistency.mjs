/**
 * 一致性检查脚本（不参与构建，仅用于自检）
 *
 * 检查四件事：
 *  1. 路由表里每个 `() => import('@/views/xxx.vue')` 的目标文件是否真实存在；
 *  2. 所有 `.vue` 里引用的 `/static/...` 资源是否都在 `public/static/` 下；
 *  3. 模板里绑定的 `@click` / `@change` 等方法名是否在 `<script setup>` 中定义（防手误）；
 *  4. API 层是否覆盖了小程序源码 `pages/api/api.js` 里的全部业务函数名。
 *
 * 用法：node scripts/check-consistency.mjs
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const srcDir = join(root, 'src')
const sourceApi = join(
  root,
  '.reverse-engineering',
  'v2-source-utf8',
  'pages',
  'api',
  'api.js',
)

let failures = 0
const fail = (msg) => {
  failures += 1
  console.log(`  ✗ ${msg}`)
}
const ok = (msg) => console.log(`  ✓ ${msg}`)

function walk(dir, ext) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) out.push(...walk(p, ext))
    else if (p.endsWith(ext)) out.push(p)
  }
  return out
}

/* 1. 路由 -> 视图文件 */
console.log('\n[1] 路由表指向的视图文件是否存在')
const routerSrc = readFileSync(join(srcDir, 'router', 'index.ts'), 'utf8')
const viewImports = [...routerSrc.matchAll(/import\(\s*'@\/([^']+)'\s*\)/g)].map((m) => m[1])
for (const rel of viewImports) {
  const target = join(srcDir, rel.replace(/^views\//, 'views/'))
  if (existsSync(target)) ok(`${rel}`)
  else fail(`${rel} 不存在（${relative(root, target)}）`)
}

/* 2. /static 资源 */
console.log('\n[2] 模板引用的 /static 资源是否存在')
const staticRefs = new Set()
for (const file of walk(srcDir, '.vue')) {
  const text = readFileSync(file, 'utf8')
  for (const m of text.matchAll(/['"](\/static\/[^'"]+)['"]/g)) staticRefs.add(m[1])
}
for (const ref of [...staticRefs].sort()) {
  const target = join(root, 'public', ref.replace(/^\//, ''))
  if (existsSync(target)) ok(`${ref}`)
  else fail(`${ref} 缺失（期望在 public${ref}）`)
}
if (staticRefs.size === 0) fail('未在模板中发现任何 /static 引用（可能解析失败）')

/* 3. 模板事件名 vs script 定义 */
console.log('\n[3] 模板绑定的事件方法是否已定义')
/**
 * 以下名字是**成员方法调用**或已知的内联表达式，不是模板所在组件的本地方法：
 *  - push / replace / back：`router.xxx()`（已在 BUILTIN 里按全名匹配）
 *  - addItem / subItem：`cart.xxx()`（来自 Pinia store）
 */
const SKIP_NAMES = new Set(['push', 'replace', 'back', 'addItem', 'subItem', 'emit'])
/** 以全名出现在模板里的内联表达式，不属于组件方法 */
const BUILTIN = new Set(['$event'])
for (const file of walk(srcDir, '.vue')) {
  const text = readFileSync(file, 'utf8')
  const scriptMatch = text.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)
  if (!scriptMatch) continue
  const script = scriptMatch[1]
  const templateMatch = text.match(/<template>([\s\S]*)<\/template>/)
  if (!templateMatch) continue
  const template = templateMatch[1]

  const handlers = new Set()
  for (const m of template.matchAll(/@([a-zA-Z-]+)="([^"]+)"/g)) {
    const expr = m[2]
    // 只取「裸的名字()」或「结尾的 .name()」，跳过 obj.method() 这种成员调用
    for (const call of expr.matchAll(/(^|[^\w.$])([A-Za-z_$][\w$]*)\s*\(/g)) handlers.add(call[2])
  }
  for (const name of handlers) {
    if (BUILTIN.has(name) || SKIP_NAMES.has(name)) continue
    const declared =
      new RegExp(
        `(async\\s+function\\s+${name}\\b)|(function\\s+${name}\\b)|(\\bconst\\s+${name}\\b)|(\\blet\\s+${name}\\b)|(\\b${name}\\s*:)`,
      ).test(script)
    if (declared) ok(`${relative(srcDir, file)} -> ${name}`)
    else fail(`${relative(srcDir, file)} 模板绑定了 ${name}()，但 <script setup> 中未定义`)
  }
}

/* 4. API 覆盖度 */
console.log('\n[4] API 层是否覆盖源码 api.js 的业务函数')
if (!existsSync(sourceApi)) {
  console.log('  ! 找不到源码 api.js，跳过（该检查仅在本地有逆向资料时有效）')
} else {
  const sourceText = readFileSync(sourceApi, 'utf8')
  const sourceFns = [...sourceText.matchAll(/export const (\w+)/g)].map((m) => m[1])
  const moduleText = walk(join(srcDir, 'api', 'modules'), '.ts')
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n')
  const moduleFns = new Set(
    [...moduleText.matchAll(/export\s+(?:async\s+)?function\s+(\w+)/g)].map((m) => m[1]),
  )

  // 源码函数名 -> 本工程使用的等价名（语义对应，已逐一核对）
  const alias = {
    getAll: 'dishCategoryList',
    getList: 'dishCategoryList',
    getDishList: 'dishPageList',
    getMoreNorm: 'dishFlavor',
    getDishDetail: 'setmealDishList',
    querySetmeaList: 'setmealListByCategoryId',
    getCategoryList: 'categoryList',
    dishListByCategoryId: 'dishListByCategoryId',
    commonDownload: 'commonDownload',
    querySetmealDishById: 'setmealDishList',
    getShopStatus: 'getShopStatus',
    getShopPhone: 'getShopPhone',
    getOrderPage: 'getOrderHistory',
    getOrderDetail: 'getOrderDetail',
    cancelOrder: 'cancelOrder',
    reminderOrder: 'reminderOrder',
    paymentOrder: 'paymentOrder',
    repetitionOrder: 'repetitionOrder',
    oneOrderAgain: 'oneOrderAgain',
    queryOrderUserPage: 'queryOrderUserPage',
    submitOrderSubmit: 'submitOrder',
    getShoppingCartList: 'getShoppingCartList',
    newAddShoppingCartAdd: 'addShoppingCart',
    newShoppingCartSub: 'subShoppingCart',
    delShoppingCart: 'cleanShoppingCart',
    addShoppingCart: 'addShoppingCartLegacy',
    editHoppingCart: 'editShoppingCart',
    addDish: 'addDish',
    delDish: 'decreaseDish',
    clearOrder: 'cleanShopCart',
    payOrder: 'payOrderByTable',
    openTable: 'openTable',
    getTableState: 'getTableState',
    getTableOrderDishList: 'getTableOrderDishList',
    userLogin: 'userLogin',
    queryAddressBookList: 'queryAddressBookList',
    getAddressBookDefault: 'getAddressBookDefault',
    queryAddressBookById: 'queryAddressBookById',
    addAddressBook: 'addAddressBook',
    editAddressBook: 'editAddressBook',
    delAddressBook: 'delAddressBook',
    putAddressBookDefault: 'putAddressBookDefault',
  }
  const missing = []
  for (const fn of sourceFns) {
    const target = alias[fn] ?? fn
    if (!moduleFns.has(target)) missing.push(`${fn} -> ${target}`)
  }
  if (missing.length === 0) ok(`源码 ${sourceFns.length} 个业务函数全部有对应实现`)
  else for (const m of missing) fail(`缺少对应实现：${m}`)
}

console.log(`\n==== 结论：${failures === 0 ? '全部通过' : `${failures} 项失败`} ====\n`)
process.exit(failures === 0 ? 0 : 1)
