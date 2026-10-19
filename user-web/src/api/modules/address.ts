/**
 * 地址簿接口
 * 源码对应：`pages/api/api.js` 的
 *   queryAddressBookList、getAddressBookDefault、queryAddressBookById、
 *   addAddressBook、editAddressBook、delAddressBook、putAddressBookDefault
 *
 * ⚠️ 文档已知缺陷（如实保留）：
 *  - `GET /user/addressBook/list` 的标题是"查询当前登录用户的所有地址信息"，
 *    但文档里 `data` 是**单个 object 而不是数组**（14 个子字段全被标为必填）。
 *    源码调用后直接当数组用（`this.addressList = res.data` 后 `v-for`），
 *    因此实际后端返回数组的可能性更大，本工程按"数组，但也容忍单对象"处理，
 *    见 `normalizeAddressList()`。
 *  - `DELETE /user/addressBook` 的 `id` 是 **query 参数**（路径里没有 `{id}`）；
 *    源码写成了 `/user/addressBook/?id=${id}` 同时又把 id 塞进 body。
 */
import { request } from '@/api/request'
import type { AddressBook, ApiData, ApiResult } from '@/types/api'

/** 带 `R<T>` 包装、`data` 可能缺省的通用返回 */
type Envelope<T> = { code: number; msg?: string | null; data?: T }

/** 新增地址入参 —— 文档 `AddressBook`，必填 `phone` / `detail` / `sex` */
export type AddressBookAddDTO = Partial<AddressBook> & {
  phone: string
  detail: string
  sex: string
}

/** 修改地址入参 —— 在新增基础上必填 `id` */
export type AddressBookEditDTO = AddressBookAddDTO & { id: number | string }

/**
 * `GET /user/addressBook/list` —— 查询当前登录用户的所有地址信息
 * 返回值做一次归一化：文档 schema 写的是单对象，源码按数组使用。
 */
export async function queryAddressBookList() {
  const res = await request.get<ApiResult<'get_user_addressBook_list'>>('/user/addressBook/list')
  return { ...res, data: normalizeAddressList(res.data) }
}

/**
 * 把接口返回归一化成数组。
 * 文档 schema 是单 object，但源码按数组消费（`v-for="item in addressList"`）。
 * 两种都可能，这里统一成数组，避免页面拿到 object 时渲染异常。
 */
export function normalizeAddressList(
  data: ApiData<'get_user_addressBook_list'> | AddressBook | undefined | null,
): AddressBook[] {
  if (!data) return []
  if (Array.isArray(data)) return data as AddressBook[]
  // 单对象：只有当它至少含一个地址字段时才当作一条记录
  const maybe = data as AddressBook
  if (maybe.id !== undefined || maybe.detail !== undefined || maybe.consignee !== undefined) {
    return [maybe]
  }
  return []
}

/**
 * `GET /user/addressBook/default` —— 查询默认地址
 * 源码：`onLoad` 里若没有选过地址就调它来填充收货人/电话/地址。
 */
export function getAddressBookDefault() {
  return request.get<ApiResult<'get_user_addressBook_default'>>('/user/addressBook/default')
}

/**
 * `GET /user/addressBook/{id}` —— 根据 id 查询地址
 * 源码：编辑地址页回显用。
 */
export function queryAddressBookById(params: { id: number | string }) {
  return request.get<ApiResult<'get_user_addressBook_id'>>(`/user/addressBook/${params.id}`)
}

/** `POST /user/addressBook` —— 新增地址 */
export function addAddressBook(params: AddressBookAddDTO) {
  return request.post<ApiResult<'post_user_addressBook'>>('/user/addressBook', params)
}

/** `PUT /user/addressBook` —— 根据 id 修改地址 */
export function editAddressBook(params: AddressBookEditDTO) {
  return request.put<ApiResult<'put_user_addressBook'>>('/user/addressBook', params)
}

/**
 * `DELETE /user/addressBook?id={id}` —— 根据 id 删除地址
 * 文档里 `id` 是 **query 参数**；源码写成 `/user/addressBook/?id=${id}` 且同时带 body，
 * 这里按文档为准（query），并去掉源码那个多余的 `/`。
 */
export function delAddressBook(id: number | string) {
  return request.delete<ApiResult<'delete_user_addressBook'>>('/user/addressBook', {
    params: { id },
  })
}

/**
 * `PUT /user/addressBook/default` —— 设置默认地址
 * 源码：地址列表页点"设为默认地址"时传 `{ id }`。
 */
export function putAddressBookDefault(params: { id: number | string }) {
  return request.put<ApiResult<'put_user_addressBook_default'>>('/user/addressBook/default', params)
}

export type { AddressBook, Envelope }
