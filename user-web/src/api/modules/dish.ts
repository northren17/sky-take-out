/**
 * 菜品 / 分类 / 套餐 / 文件下载接口
 * 源码对应：`pages/api/api.js` 的
 *   getCategoryList、dishListByCategoryId、querySetmeaList、querySetmealDishById、commonDownload
 */
import { request } from '@/api/request'
import type {
  ApiResult,
  Dish,
  DishItem,
  Setmeal,
  SourceDishCategory,
} from '@/types/api'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/**
 * `GET /user/category/list` —— 条件查询分类
 * ⚠️ 源码 v2.0 首页调用的是 **源码独有的 `/user/dish/category`（无参）**，
 *    见下方 `dishCategoryList()`；这个带 `type` 参数的文档接口在小程序里未被调用。
 */
export function categoryList(params?: { type?: number }) {
  return request.get<ApiResult<'get_user_category_list'>>('/user/category/list', { params })
}

/**
 * `GET /user/dish/category` —— 首页左侧分类列表
 * ⚠️ **源码独有接口**：day01 的 72 个接口中不存在此路径（已逐条核对）。
 *    类型按源码用法定义（首页读 `id` / `name` / `type`）。
 */
export function dishCategoryList() {
  return request.get<Envelope<SourceDishCategory[]>>('/user/dish/category')
}

/**
 * `GET /user/dish/list` —— 根据分类 id 查询菜品
 * 源码 `getDishListDataes` 在 `params.type === 1` 时走这里，只传 `{ categoryId }`。
 */
export function dishListByCategoryId(params: { categoryId: number | string }) {
  return request.get<ApiResult<'get_user_dish_list'>>('/user/dish/list', { params })
}

/**
 * `GET /user/setmeal/list` —— 根据分类 id 查询套餐
 * 源码在 `params.type !== 1`（即套餐分类）时走这里，同样只传 `{ categoryId }`。
 */
export function setmealListByCategoryId(params: { categoryId: number | string }) {
  return request.get<ApiResult<'get_user_setmeal_list'>>('/user/setmeal/list', { params })
}

/**
 * `GET /user/setmeal/dish/{id}` —— 根据套餐 id 查询包含的菜品
 * 源码 `openDetailHandle` 中 `item.type === 2` 时调用，参数是裸 id。
 */
export function setmealDishList(id: number | string) {
  return request.get<ApiResult<'get_user_setmeal_dish_id'>>(`/user/setmeal/dish/${id}`)
}

/**
 * `GET /user/dish/dishPageList/{categoryId}/{type}/{page}/{pageSize}`
 * ⚠️ **源码独有接口**，且 v2.0 首页并未调用它（首页走 `dishListByCategoryId`），
 *    属源码里定义但未被调用的接口。保留以对齐接口清单。
 */
export function dishPageList(params: {
  categoryId: number | string
  type: number | string
  page: number | string
  pageSize: number | string
}) {
  const { categoryId, type, page, pageSize } = params
  return request.get<Envelope<Dish[]>>(
    `/user/dish/dishPageList/${categoryId}/${type}/${page}/${pageSize}`,
  )
}

/**
 * `GET /user/dish/flavor/{dishId}` —— 菜品规格
 * ⚠️ **源码独有接口**，源码里只在注释中出现过（首页实际读 `DishVO.flavors`）。
 */
export function dishFlavor(dishId: number | string) {
  return request.get<Envelope<DishItem[]>>(`/user/dish/flavor/${dishId}`)
}

/**
 * `GET /user/common/download?name=` —— 文件下载
 * ⚠️ 源码里 api.js 定义的是 `/user/common/download`，但 `getNewImage()` 实际拼的是
 *    无 `/user` 前缀的 `/common/download?name=`，两者不一致。图片地址一律走
 *    `getImageUrl()`（可配置前缀），此函数仅为对齐接口清单保留。
 */
export function commonDownload(params: { name: string }) {
  return request.get<Envelope<string>>('/user/common/download', { params })
}

/** 供页面复用的类型再导出 */
export type { Dish, Setmeal, DishItem }
