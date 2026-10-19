/**
 * 响应包装与类型工具
 *
 * 类型事实来源（按优先级）：
 *  1. `src/types/openapi.d.ts` —— 由 `pnpm gen:api` 从 day01 的 Apifox 导出转换生成，
 *     覆盖 **72 个接口 / 62 条路径**，是本工程大部分类型的依据。
 *  2. 本文件"源码独有"段落 —— 小程序 v2.0 源码里实际调用、但 day01 文档中**不存在**的路径，
 *     类型按小程序源码的实际用法（字段名 + 单位）定义，并在注释中标注来源。
 *     （已逐条核对：这些路径在 72 个已收录接口中命中 0。）
 *
 * 文档自身的缺陷（如实保留，不做美化）：
 *  - 所有 query 参数在源文档中没有 `type`，转换脚本按 `example` 做了保守推断；
 *  - 所有路径参数的必填性在源文档中为空，统一按必填 string 处理；
 *  - 有 17 个接口的响应 `data` 在文档里只有 `{}`，其 `data` 类型会退化为 `Record<string, never>`；
 *  - `GET /user/order/historyOrders` 在文档里由示例响应反推生成，`msg` 原文类型是 `null`，
 *    且 `userName` / `cancelReason` / `cancelTime` / `deliveryTime` 等字段原文类型也是 `null`。
 */
import type { operations, paths } from './openapi'

/** 全部已收录路径 */
export type ApiPath = keyof paths

/** 已收录的操作（operationId） */
export type ApiOperationId = keyof operations

/**
 * 取某个接口 200 响应的**整个响应体**（即后端 `R<T>` 包装自身，不解包 `data`）。
 * 例：`ApiResult<'get_user_dish_list'>`
 */
export type ApiResult<Id extends ApiOperationId> =
  operations[Id]['responses'][200]['content']['application/json']

/**
 * 取某个接口 200 响应的 `data` 部分（自动剥离 `R<T>` 包装）。
 * 例：`ApiData<'get_user_dish_list'>` -> `DishVO[] | undefined`
 */
export type ApiData<Id extends ApiOperationId> = ApiResult<Id> extends { data?: infer D }
  ? D
  : never

/** 取某个接口的 query 参数 */
export type ApiQuery<Id extends ApiOperationId> = operations[Id]['parameters'] extends {
  query?: infer Q
}
  ? Q
  : never

/** 取某个接口的请求体 */
export type ApiBody<Id extends ApiOperationId> = operations[Id]['requestBody'] extends {
  content: { 'application/json': infer B }
}
  ? B
  : never

/**
 * 运行时业务常量（`SUCCESS_CODE` / `ORDER_STATUS` / `PAY_METHOD` /
 * `DELIVERY_STATUS` / `TABLEWARE_STATUS`）定义在 `src/constants/business.ts`。
 * 本文件是纯类型声明（构建时会被擦除），不能承载运行时导出，所以这里只做再导出，
 * 方便 `import { SUCCESS_CODE, type Dish } from '@/types/api'` 这种混用写法。
 */
export {
  SUCCESS_CODE,
  SUCCESS_CODES,
  ORDER_STATUS,
  PAY_METHOD,
  DELIVERY_STATUS,
  TABLEWARE_STATUS,
  PAY_TIMEOUT_MS,
} from '@/constants/business'

/* -------------------------------------------------------------------------- */
/* 以下为「源码独有」接口的类型                                                 */
/* 这些路径在 day01 的 72 个接口中不存在，类型按小程序 v2.0 源码的实际用法定义。   */
/* -------------------------------------------------------------------------- */

/**
 * `GET /user/dish/category` —— 首页菜品分类列表
 * 源码出处：`pages/api/api.js` 的 `getList`；首页 `init()` 里 `res.data` 直接赋给
 * `typeListData`，模板读 `item.name` / `item.type` / `item.id`。
 */
export interface SourceDishCategory {
  id?: number
  name?: string
  /** 1 = 菜品分类，2 = 套餐分类（首页据此决定走菜品还是套餐接口） */
  type?: number
  sort?: number
  status?: number
}

/**
 * `GET /user/dish/dishPageList/{categoryId}/{type}/{page}/{pageSize}` —— 分类下菜品分页
 * 源码出处：`getDishList`。**注意：v2.0 首页实际未调用它**（首页走 `dishListByCategoryId`），
 * 属于源码里定义但未被调用的接口，此处仅为保持接口清单完整而收录。
 */
export interface SourceDishPageQuery {
  categoryId: string | number
  type: string | number
  page: string | number
  pageSize: string | number
}

/**
 * `GET /user/dish/flavor/{dishId}` —— 菜品规格
 * 源码出处：`getMoreNorm`。源码里**只在注释中调用过**，首页实际用 `DishVO.flavors`。
 */
export interface SourceDishFlavorItem {
  id?: number
  dishId?: number
  name?: string
  /** JSON 字符串形式的可选值数组，源码里 `JSON.parse(obj.value)` 后使用 */
  value?: string
}

/**
 * `GET /user/common/download?name=` —— 图片下载
 * 源码出处：`getNewImage()` 实际拼的是 `${baseUrl}/common/download?name=${image}`（无 `/user` 前缀），
 * 而 api.js 里另有一个未被调用的 `/user/common/download`。两者不一致，故本工程把前缀做成
 * `.env` 配置项 `VITE_IMAGE_PREFIX`，默认取源码真正在用的那个。
 */
export interface SourceCommonDownloadQuery {
  name: string
}

/**
 * `GET /user/shop/phone` —— 店铺联系电话
 * 源码出处：`pages/index/index.js` 的 `getShopPhone()`：
 * 调用成功后 `phoneData` 与 Vuex 都被**硬编码**成 `'400-618-4000'`，
 * 即接口返回值实际未被使用。本工程保留该调用以维持一致的接口清单，
 * 但展示值与兜底值取 `.env` 的 `VITE_SHOP_PHONE`。
 */
export type SourceShopPhone = string

/**
 * `GET /user/order/userPage` —— 源码中**未被调用**的接口
 * 源码出处：`queryOrderUserPage`。v2.0 的历史订单页与"我的"页都改用
 * `getOrderPage` -> `GET /user/order/historyOrders`，此接口属死代码。
 */
export interface SourceOrderUserPageQuery {
  page: number
  pageSize: number
  status?: number | string
}

/**
 * 桌台扫码点餐相关（`/user/table/*`、`/user/order/shopCart/{tableId}` 等）
 * 源码出处：`openTable` / `getTableState` / `getTableOrderDishList`。
 * **在 v2.0 的 11 个页面中均无调用点**（属于更早的点餐模式遗留），
 * 且 day01 文档中完全没有 table 相关接口。本工程**不实现桌台点餐**，
 * 仅在此登记这些路径的存在，避免日后误以为漏做。
 */
export interface SourceTableOpenQuery {
  tableId: string | number
  seatNumber: string | number
}

export interface SourceTableStateQuery {
  shopId: string | number
  storeId: string | number
  tableId: string | number
}

/* -------------------------------------------------------------------------- */
/* 从生成类型中提炼的常用别名（供 API 层与页面复用）                              */
/* -------------------------------------------------------------------------- */

/** 菜品（`GET /user/dish/list` 的 data 元素），含前端补充的 `type` / `dishNumber` */
type DishFromDoc = NonNullable<ApiData<'get_user_dish_list'>>[number]
export type Dish = DishFromDoc & {
  /** 1 = 菜品，2 = 套餐；由源码 `getDishListDataes` 在映射时补上 */
  type?: number
  /** 源码在列表项上维护的"已加入购物车数量" */
  dishNumber?: number
  /** 源码在列表项上维护的实时数量字段 */
  newCardNumber?: number
  /**
   * 源码在选中规格后把 `flavorDataes` 序列化写回该字段：
   * `item.flavorRemark = JSON.stringify(this.flavorDataes)`
   */
  flavorRemark?: string
  /**
   * 源码在加/减菜时都会读它：
   * `if (item.dishFlavor !== '' && item.dishFlavor) { dishFlavorDatas = item.dishFlavor }`
   * —— 该字段**不在文档的 DishVO 里**，是源码运行时用到的字段（购物车里也有同名字段）。
   */
  dishFlavor?: string | null
}

/** 套餐（`GET /user/setmeal/list` 的 data 元素） */
export type Setmeal = NonNullable<ApiData<'get_user_setmeal_list'>>[number]

/** 套餐内菜品（`GET /user/setmeal/dish/{id}` 的 data 元素） */
export type DishItem = NonNullable<ApiData<'get_user_setmeal_dish_id'>>[number]

/** 购物车条目（`GET /user/shoppingCart/list` 的 data 元素） */
export type ShoppingCartItem = NonNullable<ApiData<'get_user_shoppingCart_list'>>[number]

/** 地址簿（以 `GET /user/addressBook/default` 为准，字段类型为 int64/int32） */
export type AddressBook = NonNullable<ApiData<'get_user_addressBook_default'>>

/** 下单返回（`POST /user/order/submit` 的 data） */
export type OrderSubmitVo = NonNullable<ApiData<'post_user_order_submit'>>

/** 支付返回（`PUT /user/order/payment` 的 data） */
export type OrderPaymentVo = NonNullable<ApiData<'put_user_order_payment'>>

/**
 * 历史订单分页（`GET /user/order/historyOrders` 的 data）
 * 文档中该接口由示例响应反推，`records` 内的 `userName`/`cancelReason`/`cancelTime`/`deliveryTime`
 * 以及 `msg` 的原文类型都是 `null`，这里以生成结果为准（`null` 在 TS 里就是 `null`）。
 */
export type OrderHistoryPage = NonNullable<ApiData<'get_user_order_historyOrders'>>

/** 历史订单条目 */
export type OrderHistoryItem = NonNullable<OrderHistoryPage['records']>[number] &
  Partial<OrderPricingSnapshot>

/** 订单详情（`GET /user/order/orderDetail/{id}` 的 data） */
export type OrderDetailVo = NonNullable<ApiData<'get_user_order_orderDetail_id'>> &
  Partial<OrderPricingSnapshot>

/** Coupon/order pricing values returned for orders created after coupon support was enabled. */
export interface OrderPricingSnapshot {
  itemsAmount: number
  packFee: number
  deliveryFee: number
  couponDiscount: number
  couponNameSnapshot: string | null
}
