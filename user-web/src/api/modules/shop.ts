/**
 * 店铺相关接口
 * 源码对应：`pages/api/api.js` 的 `getShopStatus`、`getShopPhone`
 */
import { request } from '@/api/request'
import type { ApiResult, SourceShopPhone } from '@/types/api'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/**
 * `GET /user/shop/status` —— 获取营业状态
 * 文档：`data` 是**裸整数**，1 为营业，0 为打烊。
 * 源码首页把它存进 `shopStatus` 与 Vuex `shopStatus`，模板据此显示"营业中/休息中"
 * 并在打烊时显示遮罩。
 */
export function getShopStatus() {
  return request.get<ApiResult<'get_user_shop_status'>>('/user/shop/status')
}

/**
 * `GET /user/shop/phone` —— 获取店铺电话
 * ⚠️ **源码独有接口**（day01 的 72 个接口里没有，也没有任何店铺电话字段）。
 * 源码调用成功后**并不使用返回值**，而是把电话硬编码为 `'400-618-4000'`；
 * 本工程保留调用以对齐接口清单，展示值以 `.env` 的 `VITE_SHOP_PHONE` 为准。
 */
export function getShopPhone() {
  return request.get<Envelope<SourceShopPhone>>('/user/shop/phone')
}
