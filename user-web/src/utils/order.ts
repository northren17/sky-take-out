/**
 * 订单相关的展示工具
 * 对应小程序 `utils/index.js` 的 `statusWord` / `getOvertime` / `getLableVal` / `dateFormat` / `presentFormat`
 */
import type { OrderHistoryItem } from '@/types/api'

/**
 * 订单状态文案（历史订单页 / 我的页用法）
 * 源码原文：
 *   if (status===1 && time>0) return '待付款'
 *   else if (status===6 || (time<0 && status===1)) return '已取消'
 *   2→'待接单' 3→'待派送' 4→'派送中' 5→'已完成' 7→'已退款'
 * @param status 订单状态
 * @param overtimeMs 距超时剩余毫秒（大于 0 表示还能付款），可选
 */
export function statusWord(status?: number, overtimeMs?: number): string {
  const s = Number(status)
  if (s === 1 && overtimeMs !== undefined && overtimeMs > 0) return '待付款'
  if (s === 6 || (overtimeMs !== undefined && overtimeMs < 0 && s === 1)) return '已取消'
  switch (s) {
    case 1:
      return '待付款'
    case 2:
      return '待接单'
    case 3:
      return '待派送'
    case 4:
      return '派送中'
    case 5:
      return '已完成'
    case 7:
      return '已退款'
    default:
      return '未知状态'
  }
}

/**
 * 订单详情页的状态文案（源码在 `pages/details/index.js` 里另有一套，措辞不同）
 * 源码原文：`if (timeout && status===1 || status===6) return '订单已取消'`，
 * 2→'等待商户接单' 3→'商家已接单' 4→'订单派送中' 5→'订单已完成'，
 * 并且对 status 1（未超时）和 7 都**不返回值**（导致标题空白，属源码缺陷）。
 * 这里为 1 和 7 补上合理文案，避免空白。
 */
export function statusWordDetail(status?: number, timeout = false): string {
  const s = Number(status)
  if ((timeout && s === 1) || s === 6) return '订单已取消'
  switch (s) {
    case 1:
      return '等待支付'
    case 2:
      return '等待商户接单'
    case 3:
      return '商家已接单'
    case 4:
      return '订单派送中'
    case 5:
      return '订单已完成'
    case 7:
      return '已退款'
    default:
      return '未知状态'
  }
}

/** 下单后 15 分钟支付时限，返回剩余毫秒（对齐源码 `getOvertime`） */
export function getOvertime(orderTime?: string): number {
  if (!orderTime) return 0
  const end = Date.parse(orderTime.replace(/-/g, '/'))
  if (Number.isNaN(end)) return 0
  return 15 * 60 * 1000 - (Date.now() - end)
}

/** 地址标签文案（源码 `getLableVal`，1 公司 / 2 家 / 3 学校 / 其它） */
export function getLabelText(label?: string | number | null): string {
  switch (String(label ?? '')) {
    case '1':
      return '公司'
    case '2':
      return '家'
    case '3':
      return '学校'
    default:
      return '其他'
  }
}

/** 计算订单明细的件数与金额（分），对齐源码 `numes()` */
export function sumOrderDetail(list?: OrderHistoryItem['orderDetailList']) {
  let count = 0
  let totalFen = 0
  for (const item of list ?? []) {
    count += Number(item.number ?? 0)
    totalFen += Number(item.number ?? 0) * Number(item.amount ?? 0)
  }
  return { count, totalFen }
}
