/**
 * 购物车接口
 * 源码对应：`pages/api/api.js` 的
 *   getShoppingCartList、newAddShoppingCartAdd、newShoppingCartSub、delShoppingCart
 *
 * 说明（已核对源码调用点）：
 *  - v2.0 **实际在用**：`/user/shoppingCart/list`、`/user/shoppingCart/add`、
 *    `/user/shoppingCart/sub`、`/user/shoppingCart/clean`
 *  - v2.0 **未被调用**（死代码，仅为对齐接口清单保留）：`POST /user/shoppingCart`、
 *    `PUT /user/shoppingCart`、以及源码独有的 `/user/order/addDish`、
 *    `/user/order/decreaseDish/{tableId}/{dishId}`、`/user/order/cleanShopCart/{tableId}`
 */
import { request } from '@/api/request'
import type { ApiResult, ShoppingCartItem } from '@/types/api'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/**
 * 购物车加减入参 —— 文档 `ShoppingCartDTO`
 * 注意：文档里该 DTO 的 `required` 是**空数组**，三个字段都没标必填。
 */
export interface ShoppingCartDTO {
  /** 口味，源码里是 `flavorRemark.join(',')` 之类拼出的字符串 */
  dishFlavor?: string | null
  /** 菜品 id；与 setmealId 二选一 */
  dishId?: number
  /** 套餐 id；与 dishId 二选一 */
  setmealId?: number
}

/**
 * `GET /user/shoppingCart/list` —— 查看购物车
 * 源码调用时传的是空对象 `{}`。
 */
export function getShoppingCartList() {
  return request.get<ApiResult<'get_user_shoppingCart_list'>>('/user/shoppingCart/list')
}

/**
 * `POST /user/shoppingCart/add` —— 添加购物车（加菜）
 * 源码：菜品传 `{ dishFlavor, dishId }`，套餐传 `{ setmealId }`（丢弃 dishFlavor）。
 */
export function addShoppingCart(params: ShoppingCartDTO) {
  return request.post<ApiResult<'post_user_shoppingCart_add'>>('/user/shoppingCart/add', params)
}

/**
 * `POST /user/shoppingCart/sub` —— 删除购物车中一个商品（减菜）
 * 源码减菜时 `dishFlavor` 只取 `flavorRemark[0]`，与加菜取全部规格**不一致**（源码如此）。
 */
export function subShoppingCart(params: ShoppingCartDTO) {
  return request.post<ApiResult<'post_user_shoppingCart_sub'>>('/user/shoppingCart/sub', params)
}

/**
 * `DELETE /user/shoppingCart/clean` —— 清空购物车
 * 源码里"再来一单"和"清空购物车"都会先调用它。
 */
export function cleanShoppingCart() {
  return request.delete<ApiResult<'delete_user_shoppingCart_clean'>>('/user/shoppingCart/clean')
}

/* ------------------------- 以下为 v2.0 未调用的接口 ------------------------- */

/** `POST /user/shoppingCart` —— 文档接口，源码 v2.0 未调用 */
export function addShoppingCartLegacy(params: ShoppingCartDTO) {
  return request.post<Envelope<unknown>>('/user/shoppingCart', params)
}

/** `PUT /user/shoppingCart` —— 文档接口，源码 v2.0 未调用 */
export function editShoppingCart(params: ShoppingCartDTO) {
  return request.put<Envelope<unknown>>('/user/shoppingCart', params)
}

/** `POST /user/order/addDish` —— 源码独有，v2.0 未调用 */
export function addDish(params: ShoppingCartDTO) {
  return request.post<Envelope<unknown>>('/user/order/addDish', params)
}

/** `GET /user/order/decreaseDish/{tableId}/{dishId}` —— 源码独有，v2.0 未调用 */
export function decreaseDish(params: { tableId: string | number; dishId: string | number }) {
  return request.get<Envelope<unknown>>(
    `/user/order/decreaseDish/${params.tableId}/${params.dishId}`,
  )
}

/** `GET /user/order/cleanShopCart/{tableId}` —— 源码独有，v2.0 未调用 */
export function cleanShopCart(tableId: string | number) {
  return request.get<Envelope<unknown>>(`/user/order/cleanShopCart/${tableId}`)
}

export type { ShoppingCartItem }
