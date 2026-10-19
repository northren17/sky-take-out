import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AddressBook, OrderSubmitVo } from '@/types/api'

/**
 * 跨页面共享的临时业务状态
 * 对应小程序 Vuex 的：
 *  - `addressData`    地址选择结果（订单页 <-> 地址页）
 *  - `remarkData`     备注（订单页 <-> 备注页）
 *  - `orderData`      刚下单返回的数据（订单页 -> 支付页 -> 成功页）
 *  - `arrivals`       预计送达时间
 *  - `addressBackUrl` 地址页返回时该回到哪个页面（对应 `/pages/order/index` 或 `/pages/my/my`）
 *
 * 小程序靠 `uni.redirectTo` 传参不方便，所以这些值放在 Vuex 里；网页版改用路由 query
 * 会更自然，但为了与源码行为一致（订单页 onLoad 读 addressData），仍保留一个轻量 store。
 */
export const useCheckoutStore = defineStore('checkout', () => {
  /** 收货地址（0 长度数组表示未选） */
  const address = ref<AddressBook | null>(null)
  /** 订单备注，最多 50 字 */
  const remark = ref('')
  /** 刚提交的订单（`POST /user/order/submit` 的 data） */
  const orderData = ref<OrderSubmitVo | null>(null)
  /** 预计送达时间，由订单页 `getHarfAnOur()` 生成 */
  const arrivalTime = ref('')
  /** 地址页返回目标：`/order` 或 `/my` */
  const addressBackPath = ref('/order')

  /** 显示用的完整地址串：省 + 市 + 区 + 详细地址（与源码拼法一致） */
  const fullAddress = computed(() => {
    const a = address.value
    if (!a) return ''
    return `${a.provinceName ?? ''}${a.cityName ?? ''}${a.districtName ?? ''}${a.detail ?? ''}`
  })

  function setAddress(value: AddressBook | null) {
    address.value = value
  }

  function setRemark(value: string) {
    remark.value = value
  }

  function setOrderData(value: OrderSubmitVo | null) {
    orderData.value = value
  }

  function setArrivalTime(value: string) {
    arrivalTime.value = value
  }

  function setAddressBackPath(value: string) {
    addressBackPath.value = value
  }

  return {
    address,
    remark,
    orderData,
    arrivalTime,
    addressBackPath,
    fullAddress,
    setAddress,
    setRemark,
    setOrderData,
    setArrivalTime,
    setAddressBackPath,
  }
})
