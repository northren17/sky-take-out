/**
 * 全局业务常量（运行时值）
 *
 * 说明：这些是**运行时**用到的值，必须放在真实 `.ts` 模块里。
 * `src/types/api.d.ts` 是纯类型声明文件（构建时会被擦除），不能承载运行时导出。
 */

/** 后端统一响应码：1 = 成功 */
export const SUCCESS_CODE = 1

/** 原实现里也接受 200（`data.code == 200 || data.code === 1`） */
export const SUCCESS_CODES: readonly number[] = [200, 1]

/**
 * 订单状态取值
 * 依据：源码 `utils/index.js` 的 `statusWord`，以及 `pages/my/my.js` 里的注释
 * `1待付款 2待接单 3已接单 4派送中 5已完成 6已取消 7退款`
 */
export const ORDER_STATUS = {
  /** 待付款 */
  UN_PAID: 1,
  /** 待接单 */
  TO_BE_CONFIRMED: 2,
  /** 已接单 / 待派送 */
  CONFIRMED: 3,
  /** 派送中 */
  DELIVERY_IN_PROGRESS: 4,
  /** 已完成 */
  COMPLETED: 5,
  /** 已取消 */
  CANCELLED: 6,
  /** 已退款 */
  REFUNDED: 7,
} as const

/** 支付方式：源码 `payMethod: this.activeRadio === 0 ? 1 : 2` */
export const PAY_METHOD = {
  WECHAT: 1,
  ALIPAY: 2,
} as const

/** 配送方式：源码 `deliveryStatus: arrivalTime === '立即派送' ? 1 : 0` */
export const DELIVERY_STATUS = {
  /** 选择具体时间 */
  SCHEDULED: 0,
  /** 立即送出 */
  IMMEDIATELY: 1,
} as const

/** 餐具数量状态：源码 `tablewareStatus`，1 = 依据餐量提供，0 = 选择具体数量 */
export const TABLEWARE_STATUS = {
  SPECIFIC: 0,
  BY_MEAL: 1,
} as const

/** 支付时限：源码到处硬编码 15 分钟 */
export const PAY_TIMEOUT_MS = 15 * 60 * 1000
