/**
 * 桌台扫码点餐接口（源码 `pages/api/api.js` 的 openTable / getTableState / getTableOrderDishList）
 *
 * ⚠️ 三点事实，避免误用：
 *  1. 这三个路径 **day01 的 72 个接口文档里完全不存在**（已逐条核对，涉及"桌台"的能力文档里一个都没有）。
 *  2. 在 v2.0 的 11 个页面中 **没有任何调用点**（源码里也属遗留/未启用代码）。
 *  3. 源码里 `getTableOrderDishList` 的 URL 有一段连续双斜杠
 *     （`/user/order/shopCart//${tableId}`），是拼接空串导致的笔误，这里保留为一个占位
 *     `tableId` 的正常写法，并在注释中记录原文。
 *
 * 本工程**不实现桌台点餐流程**，保留这些函数只为让"接口清单"与源码一致、便于日后核对。
 */
import { request } from '@/api/request'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/** `GET /user/table/open/{tableId}/{seatNumber}` —— 开桌 */
export function openTable(params: { tableId: string | number; seatNumber: string | number }) {
  return request.get<Envelope<unknown>>(
    `/user/table/open/${params.tableId}/${params.seatNumber}`,
    { params },
  )
}

/** `GET /user/table/tableStatus/{shopId}/{storeId}/{tableId}` —— 获取桌台状态 */
export function getTableState(params: {
  shopId: string | number
  storeId: string | number
  tableId: string | number
}) {
  return request.get<Envelope<unknown>>(
    `/user/table/tableStatus/${params.shopId}/${params.storeId}/${params.tableId}`,
    { params },
  )
}

/**
 * 获取购物车菜品（桌台模式）
 * 源码原文是 `url: "/user/order/shopCart//".concat(params.tableId)`（双斜杠），
 * 这里按单斜杠请求，避免后端路由匹配失败。
 */
export function getTableOrderDishList(params: { tableId: string | number }) {
  return request.get<Envelope<unknown>>(`/user/order/shopCart/${params.tableId}`, { params })
}
