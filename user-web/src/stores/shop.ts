import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getShopPhone, getShopStatus } from '@/api/modules/shop'
import { SHOP_PHONE } from '@/api/request'
import { SUCCESS_CODE } from '@/constants/business'

/**
 * 店铺状态
 * 对应小程序 Vuex 的 `shopStatus` / `shopPhone`
 */
export const useShopStore = defineStore('shop', () => {
  /** 1 = 营业中，0 = 打烊；`null` 表示尚未取到 */
  const status = ref<number | null>(null)
  /**
   * 店铺电话
   * ⚠️ 源码调用 `/user/shop/phone` 后**不使用返回值**，直接硬编码 `400-618-4000`；
   *    本工程同样以 `.env` 的 `VITE_SHOP_PHONE` 为准，接口仅作探测。
   */
  const phone = ref<string>(SHOP_PHONE)

  const isOpen = computed(() => status.value === 1)
  /** 源码只在 `shopStatus === 0` 时显示「本店已打烊」遮罩 */
  const isClosed = computed(() => status.value === 0)

  async function fetchStatus() {
    try {
      const res = await getShopStatus()
      if (res.code === SUCCESS_CODE) {
        status.value = Number(res.data)
      }
    } catch {
      // 打烊状态取不到时保持 null，页面不显示遮罩
    }
  }

  async function fetchPhone() {
    try {
      await getShopPhone()
    } catch {
      // 忽略：电话以本地配置为准
    }
  }

  async function fetchAll() {
    // 电话展示值来自 VITE_SHOP_PHONE。当前后端没有 /user/shop/phone，且原应用
    // 也不使用该接口的返回值，因此首页只加载真正影响营业状态的接口。
    await fetchStatus()
  }

  return { status, phone, isOpen, isClosed, fetchStatus, fetchPhone, fetchAll }
})
