/**
 * 订单接口
 * 源码对应：`pages/api/api.js` 的
 *   submitOrderSubmit、getOrderPage、getOrderDetail、cancelOrder、
 *   reminderOrder、paymentOrder、repetitionOrder、oneOrderAgain、queryOrderUserPage
 */
import { request } from '@/api/request'
import type {
  ApiResult,
  OrderDetailVo,
  OrderHistoryPage,
  OrderPaymentVo,
  OrderSubmitVo,
} from '@/types/api'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/**
 * 下单入参 —— 文档 `OrdersSubmitDTO`（优惠券 ID 可选，其余字段按订单页填写）
 *
 * ⚠️ 源码实际上是这么组装的（见 `pages/order/index.js` 的 `payOrderHandle`），
 *    与文档字段对齐，但语义有两点要注意，均为**源码如此**：
 *    1. `packAmount` 传的是 `orderDishNumber`（**菜品总件数**，不是金额）；
 *    2. `amount` 传的是 `orderDishPrice`，而该值在 `computOrderInfo()` 里已经
 *       加过 `+6`（配送费）和 `+orderDishNumber`（打包费）。
 */
export interface OrdersSubmitDTO {
  /** 地址簿 id */
  addressBookId: number | string
  /** 总金额 */
  amount: number
  /** 配送状态：1 立即送出，0 选择具体时间 */
  deliveryStatus: number
  /** 预计送达时间（字符串，源码用 `presentFormat()`/`dateFormat()` 生成） */
  estimatedDeliveryTime: string
  /** 打包费 */
  packAmount: number
  /** 付款方式：1 微信，2 支付宝 */
  payMethod: number
  /** 用户选择的优惠券；优惠金额仅由后端计算 */
  userCouponId?: number
  /** 备注 */
  remark: string
  /** 餐具数量 */
  tablewareNumber: number
  /** 餐具数量状态：1 按餐量提供，0 选择具体数量 */
  tablewareStatus: number
}

/**
 * `POST /user/order/submit` —— 用户下单
 * 注意：**购物车明细不在请求体里**，由后端按当前用户购物车结算（源码如此）。
 */
export function submitOrder(params: OrdersSubmitDTO) {
  return request.post<ApiResult<'post_user_order_submit'>>('/user/order/submit', params)
}

/**
 * `PUT /user/order/payment` —— 订单支付
 * 源码：`{ orderNumber, payMethod: activeRadio === 0 ? 1 : 2 }`。
 */
export function paymentOrder(params: { orderNumber: string; payMethod: number }) {
  return request.put<ApiResult<'put_user_order_payment'>>('/user/order/payment', params)
}

/**
 * `GET /user/order/historyOrders` —— 历史订单查询
 * 源码：`{ page, pageSize: 10, status }`；`status` 为 `''` 表示全部。
 * 历史订单页与"我的"页都用它（"我的"页不传 status）。
 */
export function getOrderHistory(params: {
  page: number
  pageSize: number
  status?: number | string
}) {
  return request.get<ApiResult<'get_user_order_historyOrders'>>('/user/order/historyOrders', {
    params,
  })
}

/**
 * `GET /user/order/orderDetail/{id}` —— 查询订单详情
 * 源码传的是裸 id（不是对象）。
 */
export function getOrderDetail(id: number | string) {
  return request.get<ApiResult<'get_user_order_orderDetail_id'>>(`/user/order/orderDetail/${id}`)
}

/**
 * `PUT /user/order/cancel/{id}` —— 取消订单
 * 源码：只有 `status` 为 1 或 2 时才允许取消，否则提示"请联系商家进行取消！"。
 */
export function cancelOrder(id: number | string) {
  return request.put<ApiResult<'put_user_order_cancel_id'>>(`/user/order/cancel/${id}`)
}

/**
 * `GET /user/order/reminder/{id}` —— 催单
 * 源码：只有 `status === 2`（待接单）时按钮才出现。
 */
export function reminderOrder(id: number | string) {
  return request.get<ApiResult<'get_user_order_reminder_id'>>(`/user/order/reminder/${id}`)
}

/**
 * `POST /user/order/repetition/{id}` —— 再来一单
 * 源码：调用前会先 `cleanShoppingCart()` 清空购物车。
 */
export function repetitionOrder(id: number | string) {
  return request.post<ApiResult<'post_user_order_repetition_id'>>(
    `/user/order/repetition/${id}`,
    {},
  )
}

/* ------------------------- 以下为 v2.0 未调用的接口 ------------------------- */

/**
 * `POST /user/order/again` —— **源码独有接口**，未在 72 个文档接口中，
 * 且 v2.0 页面里也没有调用点（"再来一单"走的是 `repetitionOrder`）。
 */
export function oneOrderAgain(id: number | string) {
  return request.post<Envelope<unknown>>('/user/order/again', { id })
}

/**
 * `GET /user/order/userPage` —— **源码独有接口**，v2.0 已改用 `historyOrders`。
 * 文档中不存在该路径。
 */
export function queryOrderUserPage(params: { page: number; pageSize: number; status?: number | string }) {
  return request.get<Envelope<OrderHistoryPage>>('/user/order/userPage', { params })
}

/** `GET /user/order/pay/{tableId}/{jsCode}` —— 源码独有（桌台点餐遗留），未调用 */
export function payOrderByTable(params: { tableId: string | number; jsCode: string }) {
  return request.get<Envelope<unknown>>(`/user/order/pay/${params.tableId}/${params.jsCode}`)
}

export type { OrderSubmitVo, OrderPaymentVo, OrderHistoryPage, OrderDetailVo }
