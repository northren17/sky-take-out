/**
 * 本地后端桩服务（仅用于前端自检 / 演示，**不属于前端源码，也不参与构建**）
 *
 * 目的：前端构建产物没有 Vite 代理，HTTP 自检时调不到 http://localhost:8080，
 * 页面只能渲染空态。这个桩服务按 day01 接口文档 + 小程序 v2.0 源码里实际用到的
 * 响应结构返回数据，让"接口 → 页面"的整条链路也能被验证。
 *
 * ⚠️ 明确边界：
 *  - 真实运行时请启动真正的苍穹外卖后端（`localhost:8080`），并与 Vite 代理配合；
 *  - 本桩服务返回的是**演示数据**，不是前端 mock（前端代码里没有任何 mock 分支）。
 *
 * 用法：node scripts/mock-backend.mjs [port]     默认 8080
 */
import { createServer } from 'node:http'

const PORT = Number(process.argv[2] ?? 8080)

const ok = (data) => ({ code: 1, msg: null, data })
const now = () => new Date().toISOString().slice(0, 19).replace('T', ' ')

/* ------------------------------ 演示数据 ------------------------------ */

const categories = [
  { id: 1, name: '热销推荐', type: 1, sort: 1, status: 1 },
  { id: 2, name: '经典川菜', type: 1, sort: 2, status: 1 },
  { id: 3, name: '超值套餐', type: 2, sort: 3, status: 1 },
]

const dishes = {
  1: [
    {
      id: 101,
      name: '宫保鸡丁',
      categoryId: 1,
      categoryName: '热销推荐',
      description: '经典川味，鸡丁爽滑花生香脆',
      image: 'gongbao.jpg',
      price: 28.0,
      status: 1,
      flavors: [],
    },
    {
      id: 102,
      name: '水煮牛肉',
      categoryId: 1,
      categoryName: '热销推荐',
      description: '麻辣鲜香，牛肉嫩滑',
      image: 'shuizhu.jpg',
      price: 48.0,
      status: 1,
      flavors: [
        { id: 1, dishId: 102, name: '辣度', value: '["微辣","中辣","特辣"]' },
        { id: 2, dishId: 102, name: '份量', value: '["小份","大份"]' },
      ],
    },
  ],
  2: [
    {
      id: 103,
      name: '麻婆豆腐',
      categoryId: 2,
      categoryName: '经典川菜',
      description: '麻辣烫口，下饭首选',
      image: 'mapo.jpg',
      price: 18.0,
      status: 1,
      flavors: [],
    },
    {
      id: 104,
      name: '鱼香肉丝',
      categoryId: 2,
      categoryName: '经典川菜',
      description: '酸甜微辣，口感丰富',
      image: 'yuxiang.jpg',
      price: 26.0,
      status: 1,
      flavors: [{ id: 3, dishId: 104, name: '辣度', value: '["不辣","微辣","中辣"]' }],
    },
  ],
}

const setmeals = {
  3: [
    {
      id: 201,
      name: '双人畅享套餐',
      categoryId: 3,
      description: '两荤一素配米饭，适合两人分享',
      image: 'setmeal2.jpg',
      price: 88.0,
      status: 1,
    },
    {
      id: 202,
      name: '单人工作日套餐',
      categoryId: 3,
      description: '一荤一素配汤，快速解决午餐',
      image: 'setmeal1.jpg',
      price: 36.0,
      status: 1,
    },
  ],
}

const setmealDishes = {
  201: [
    { name: '宫保鸡丁', copies: 1, image: 'gongbao.jpg', description: '经典川味' },
    { name: '麻婆豆腐', copies: 1, image: 'mapo.jpg', description: '麻辣烫口' },
    { name: '米饭', copies: 2, image: 'rice.jpg', description: '东北珍珠米' },
  ],
  202: [
    { name: '鱼香肉丝', copies: 1, image: 'yuxiang.jpg', description: '酸甜微辣' },
    { name: '紫菜蛋花汤', copies: 1, image: 'soup.jpg', description: '清爽解腻' },
  ],
}

const addresses = [
  {
    id: 1,
    userId: 1,
    consignee: '张三',
    phone: '13800138000',
    sex: '0',
    provinceCode: '110000',
    provinceName: '北京市',
    cityCode: '110100',
    cityName: '北京市',
    districtCode: '110105',
    districtName: '朝阳区',
    detail: '新街大道一号楼 8 层 801',
    label: '1',
    isDefault: 1,
  },
  {
    id: 2,
    userId: 1,
    consignee: '李四',
    phone: '13900139000',
    sex: '1',
    provinceCode: '310000',
    provinceName: '上海市',
    cityCode: '310100',
    cityName: '上海市',
    districtCode: '310104',
    districtName: '徐汇区',
    detail: '漕溪北路 100 号 502',
    label: '2',
    isDefault: 0,
  },
]

let cart = [
  {
    id: 1,
    dishId: 101,
    setmealId: null,
    name: '宫保鸡丁',
    image: 'gongbao.jpg',
    dishFlavor: null,
    number: 1,
    amount: 28.0,
    createTime: now(),
  },
  {
    id: 2,
    dishId: 102,
    setmealId: null,
    name: '水煮牛肉',
    image: 'shuizhu.jpg',
    dishFlavor: '微辣,大份',
    number: 2,
    amount: 48.0,
    createTime: now(),
  },
]

const orderDetails = [
  {
    id: 1,
    orderId: 9001,
    dishId: 101,
    setmealId: null,
    name: '宫保鸡丁',
    image: 'gongbao.jpg',
    dishFlavor: null,
    number: 1,
    amount: 28.0,
  },
  {
    id: 2,
    orderId: 9001,
    dishId: 102,
    setmealId: null,
    name: '水煮牛肉',
    image: 'shuizhu.jpg',
    dishFlavor: '微辣,大份',
    number: 2,
    amount: 48.0,
  },
]

const orders = [
  {
    id: 9001,
    number: 'SK20240101000001',
    status: 1,
    userId: 1,
    addressBookId: 1,
    orderTime: now(),
    checkoutTime: null,
    payMethod: 1,
    payStatus: 1,
    amount: 140.0,
    remark: '不要香菜',
    userName: null,
    phone: '13800138000',
    address: '北京市北京市朝阳区新街大道一号楼 8 层 801',
    consignee: '张三',
    cancelReason: null,
    rejectionReason: null,
    cancelTime: null,
    estimatedDeliveryTime: now(),
    deliveryStatus: 1,
    deliveryTime: null,
    packAmount: 3.0,
    tablewareNumber: 0,
    tablewareStatus: 0,
    orderDetailList: orderDetails,
  },
  {
    id: 9002,
    number: 'SK20240101000002',
    status: 2,
    userId: 1,
    addressBookId: 1,
    orderTime: '2024-01-01 12:30:00',
    checkoutTime: '2024-01-01 12:31:00',
    payMethod: 1,
    payStatus: 2,
    amount: 88.0,
    remark: '',
    userName: null,
    phone: '13800138000',
    address: '北京市北京市朝阳区新街大道一号楼 8 层 801',
    consignee: '张三',
    cancelReason: null,
    rejectionReason: null,
    cancelTime: null,
    estimatedDeliveryTime: '2024-01-01 13:30:00',
    deliveryStatus: 1,
    deliveryTime: null,
    packAmount: 0.0,
    tablewareNumber: 2,
    tablewareStatus: 0,
    orderDetailList: orderDetails.slice(0, 1),
  },
  {
    id: 9003,
    number: 'SK20240101000003',
    status: 5,
    userId: 1,
    addressBookId: 2,
    orderTime: '2023-12-31 18:10:00',
    checkoutTime: '2023-12-31 18:11:00',
    payMethod: 1,
    payStatus: 2,
    amount: 36.0,
    remark: '',
    userName: null,
    phone: '13900139000',
    address: '上海市上海市徐汇区漕溪北路 100 号 502',
    consignee: '李四',
    cancelReason: null,
    rejectionReason: null,
    cancelTime: null,
    estimatedDeliveryTime: '2023-12-31 19:10:00',
    deliveryStatus: 1,
    deliveryTime: '2023-12-31 19:05:00',
    packAmount: 0.0,
    tablewareNumber: 0,
    tablewareStatus: 0,
    orderDetailList: orderDetails.slice(0, 1),
  },
]

let addressSeq = 100

/* ------------------------------ 路由处理 ------------------------------ */

function send(res, status, body) {
  const text = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': '*',
  })
  res.end(text)
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (c) => {
      raw += c
    })
    req.on('end', () => {
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch {
        resolve({})
      }
    })
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`)
  const path = url.pathname
  const method = (req.method ?? 'GET').toUpperCase()
  const body = method === 'GET' || method === 'DELETE' ? {} : await readBody(req)
  const log = (extra = '') =>
    console.log(`[mock] ${method} ${path}${url.search}${extra ? ` ${extra}` : ''}`)

  // 分类
  if (path === '/user/dish/category') {
    log()
    return send(res, 200, ok(categories))
  }
  if (path === '/user/category/list') {
    const type = Number(url.searchParams.get('type') ?? 0)
    log(`type=${type}`)
    return send(res, 200, ok(type ? categories.filter((c) => c.type === type) : categories))
  }

  // 菜品 / 套餐
  if (path === '/user/dish/list') {
    const categoryId = Number(url.searchParams.get('categoryId') ?? 0)
    log(`categoryId=${categoryId}`)
    return send(res, 200, ok(dishes[categoryId] ?? []))
  }
  if (path === '/user/setmeal/list') {
    const categoryId = Number(url.searchParams.get('categoryId') ?? 0)
    log(`categoryId=${categoryId}`)
    return send(res, 200, ok(setmeals[categoryId] ?? []))
  }
  const setmealDishMatch = path.match(/^\/user\/setmeal\/dish\/(\d+)$/)
  if (setmealDishMatch) {
    log(`id=${setmealDishMatch[1]}`)
    return send(res, 200, ok(setmealDishes[setmealDishMatch[1]] ?? []))
  }

  // 店铺
  if (path === '/user/shop/status') {
    log()
    return send(res, 200, ok(1))
  }
  if (path === '/user/shop/phone') {
    log()
    return send(res, 200, ok('400-618-4000'))
  }

  // 购物车
  if (path === '/user/shoppingCart/list') {
    log()
    return send(res, 200, ok(cart))
  }
  if (path === '/user/shoppingCart/add' && method === 'POST') {
    log(JSON.stringify(body))
    const dishId = Number(body.dishId ?? 0)
    const setmealId = body.setmealId ? Number(body.setmealId) : null
    const found = cart.find(
      (c) => (dishId && c.dishId === dishId) || (setmealId && c.setmealId === setmealId),
    )
    if (found) {
      found.number += 1
    } else {
      const src =
        Object.values(dishes)
          .flat()
          .find((d) => d.id === dishId) ??
        Object.values(setmeals)
          .flat()
          .find((s) => s.id === setmealId)
      cart.push({
        id: cart.length + 1 + 1000,
        dishId: dishId || null,
        setmealId,
        name: src?.name ?? '未知商品',
        image: src?.image ?? 'imgDefault.png',
        dishFlavor: body.dishFlavor ?? null,
        number: 1,
        amount: src?.price ?? 0,
        createTime: now(),
      })
    }
    return send(res, 200, ok('ok'))
  }
  if (path === '/user/shoppingCart/sub' && method === 'POST') {
    log(JSON.stringify(body))
    const dishId = Number(body.dishId ?? 0)
    const setmealId = body.setmealId ? Number(body.setmealId) : null
    const idx = cart.findIndex(
      (c) => (dishId && c.dishId === dishId) || (setmealId && c.setmealId === setmealId),
    )
    if (idx >= 0) {
      cart[idx].number -= 1
      if (cart[idx].number <= 0) cart.splice(idx, 1)
    }
    return send(res, 200, ok('ok'))
  }
  if (path === '/user/shoppingCart/clean' && method === 'DELETE') {
    log()
    cart = []
    return send(res, 200, ok('ok'))
  }

  // 地址簿
  if (path === '/user/addressBook/list') {
    log()
    // 注意：文档里这个接口的 data 是单 object，真实后端更可能返回数组；
    // 桩服务返回数组，前端已做两种形态的归一化。
    return send(res, 200, ok(addresses))
  }
  if (path === '/user/addressBook/default' && method === 'GET') {
    log()
    return send(res, 200, ok(addresses.find((a) => a.isDefault === 1) ?? addresses[0]))
  }
  if (path === '/user/addressBook/default' && method === 'PUT') {
    log(JSON.stringify(body))
    const id = Number(body.id)
    addresses.forEach((a) => {
      a.isDefault = a.id === id ? 1 : 0
    })
    return send(res, 200, ok('ok'))
  }
  const addrById = path.match(/^\/user\/addressBook\/(\d+)$/)
  if (addrById && method === 'GET') {
    log(`id=${addrById[1]}`)
    return send(res, 200, ok(addresses.find((a) => a.id === Number(addrById[1])) ?? {}))
  }
  if (path === '/user/addressBook' && method === 'POST') {
    log(JSON.stringify(body))
    addressSeq += 1
    addresses.push({ ...body, id: addressSeq, userId: 1, isDefault: 0 })
    return send(res, 200, ok('ok'))
  }
  if (path === '/user/addressBook' && method === 'PUT') {
    log(JSON.stringify(body))
    const idx = addresses.findIndex((a) => a.id === Number(body.id))
    if (idx >= 0) addresses[idx] = { ...addresses[idx], ...body }
    return send(res, 200, ok('ok'))
  }
  if (path === '/user/addressBook' && method === 'DELETE') {
    log(`id=${url.searchParams.get('id')}`)
    const id = Number(url.searchParams.get('id'))
    const idx = addresses.findIndex((a) => a.id === id)
    if (idx >= 0) addresses.splice(idx, 1)
    return send(res, 200, ok('ok'))
  }

  // 优惠券：订单页用空券列表验证无优惠券时仍可正常结算。
  if (path === '/user/coupon/mine' && method === 'GET') {
    log(`page=${url.searchParams.get('page') ?? 1}`)
    return send(res, 200, ok({ total: 0, records: [] }))
  }

  // 订单
  if (path === '/user/order/submit' && method === 'POST') {
    log(JSON.stringify(body))
    const id = 9000 + orders.length + 1
    const newOrder = {
      id,
      orderNumber: `SK${String(id).padStart(14, '0')}`,
      orderAmount: Number(body.amount ?? 0),
      orderTime: now(),
    }
    orders.unshift({
      ...orders[0],
      id,
      number: newOrder.orderNumber,
      status: 1,
      orderTime: now(),
      amount: Number(body.amount ?? 0),
      remark: body.remark ?? '',
      orderDetailList: cart.map((c, i) => ({ ...orderDetails[0], ...c, id: i + 1, orderId: id })),
    })
    cart = []
    return send(res, 200, ok(newOrder))
  }
  if (path === '/user/order/payment' && method === 'PUT') {
    log(JSON.stringify(body))
    const o = orders.find((x) => x.number === body.orderNumber)
    if (o) {
      o.status = 2
      o.payStatus = 2
    }
    return send(res, 200, ok({
      nonceStr: 'mock-nonce',
      paySign: 'mock-sign',
      timeStamp: String(Date.now()),
      signType: 'RSA',
      packageStr: 'prepay_id=mock',
    }))
  }
  if (path === '/user/order/historyOrders') {
    const page = Number(url.searchParams.get('page') ?? 1)
    const pageSize = Number(url.searchParams.get('pageSize') ?? 10)
    const status = url.searchParams.get('status')
    let list = orders
    if (status !== null && status !== '') list = orders.filter((o) => String(o.status) === status)
    log(`page=${page} pageSize=${pageSize} status=${status}`)
    return send(res, 200, {
      code: 1,
      msg: null,
      data: { total: list.length, records: list.slice((page - 1) * pageSize, page * pageSize) },
    })
  }
  const orderDetail = path.match(/^\/user\/order\/orderDetail\/(\d+)$/)
  if (orderDetail) {
    log(`id=${orderDetail[1]}`)
    return send(res, 200, ok(orders.find((o) => o.id === Number(orderDetail[1])) ?? {}))
  }
  const orderCancel = path.match(/^\/user\/order\/cancel\/(\d+)$/)
  if (orderCancel) {
    log(`id=${orderCancel[1]}`)
    const o = orders.find((x) => x.id === Number(orderCancel[1]))
    if (o) o.status = 6
    return send(res, 200, ok('ok'))
  }
  const orderReminder = path.match(/^\/user\/order\/reminder\/(\d+)$/)
  if (orderReminder) {
    log(`id=${orderReminder[1]}`)
    return send(res, 200, ok('ok'))
  }
  const orderRepetition = path.match(/^\/user\/order\/repetition\/(\d+)$/)
  if (orderRepetition) {
    log(`id=${orderRepetition[1]}`)
    cart = orderDetails.map((d, i) => ({ ...d, id: i + 1, createTime: now() }))
    return send(res, 200, ok('ok'))
  }

  // 登录
  if (path === '/user/user/login' && method === 'POST') {
    log(JSON.stringify(body))
    return send(res, 200, ok({ id: 1, openid: 'mock-openid', token: 'mock-token-from-stub' }))
  }

  // 图形验证码（网页版账号密码登录用）：返回一个占位图，仅用于渲染自检
  if (path === '/user/user/captcha') {
    return send(
      res,
      200,
      ok({
        captchaKey: 'mock-captcha-key',
        // 1x1 透明 PNG
        image:
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
        expireIn: 120,
      }),
    )
  }

  // 账号注册 / 账号密码登录：桩服务不做校验，直接给一个 token
  if (
    (path === '/user/user/register' || path === '/user/user/loginByPassword') &&
    method === 'POST'
  ) {
    log(JSON.stringify(body))
    return send(
      res,
      200,
      ok({ id: 1, username: body?.username ?? 'mock-user', name: '桩用户', token: 'mock-token-from-stub' }),
    )
  }

  // 当前登录用户信息
  if (path === '/user/user/info') {
    return send(res, 200, ok({ id: 1, username: 'mock-user', name: '桩用户', phone: '13800000000' }))
  }

  // 图片下载：返回 1x1 透明 PNG，避免前端 img 报错
  if (path === '/common/download' || path === '/user/common/download') {
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
      'base64',
    )
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' })
    return res.end(png)
  }

  log('-> 404')
  return send(res, 404, { code: 0, msg: `桩服务未实现：${method} ${path}`, data: null })
})

server.listen(PORT, () => {
  console.log(`[mock] 后端桩服务已启动：http://localhost:${PORT}`)
  console.log('[mock] 这是演示数据，不是前端 mock；真实运行请启动苍穹外卖后端')
})
