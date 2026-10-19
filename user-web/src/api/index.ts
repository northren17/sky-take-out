/**
 * API 模块统一出口
 *
 * 约定：
 *  - 所有请求都返回后端的**整个响应体**（`R<T>` 包装，`{ code, msg?, data? }`），
 *    与小程序 `utils/request.js` 的行为一致，所以调用方要写 `res.data`。
 *  - 成功判定为 `code === 1`（原实现是 `code == 200 || code === 1`）。
 *  - 函数命名尽量对齐小程序 `pages/api/api.js` 的业务语义，便于与源码对照。
 */
export * as userApi from './modules/user'
export * as dishApi from './modules/dish'
export * as cartApi from './modules/cart'
export * as orderApi from './modules/order'
export * as addressApi from './modules/address'
export * as shopApi from './modules/shop'
/** 桌台扫码点餐：源码里存在但 11 个页面均无调用点，且文档中没有这些接口 */
export * as tableApi from './modules/table'

export { getImageUrl, SHOP_NAME, SHOP_PHONE, DELIVERY_FEE, request } from '@/api/request'
