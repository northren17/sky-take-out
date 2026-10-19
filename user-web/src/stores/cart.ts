import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  addShoppingCart,
  cleanShoppingCart,
  getShoppingCartList,
  subShoppingCart,
  type ShoppingCartDTO,
} from '@/api/modules/cart'
import type { ShoppingCartItem } from '@/types/api'
import { SUCCESS_CODE } from '@/constants/business'

/**
 * 金额展示换算
 * <p>
 * ⚠️ 重要修正：项目后端接口返回的金额单位是**元**，不是分。
 * 依据：数据库列为 `decimal(10,2)`（如 72.00），且后端代码里**没有任何** ×100 / ÷100 的换算，
 * 只有 `WeChatPayUtil` 在调微信支付接口时才把元 ×100 转成分（那是微信接口的要求）。
 * <p>
 * 而原实现按"分"再除以 100，导致 72.00 元的菜被显示成 0.72 元、
 * 结算时又把 87 元的订单按 8700 提交。这里直接按元返回。
 * <p>
 * 函数名 `fenToYuan` 保留，是为了不改动 15 处调用点；语义上它现在就是"规整为两位小数的元"。
 */
export function fenToYuan(amount?: number | null): number {
  const value = Number(amount ?? 0)
  if (!Number.isFinite(value)) return 0
  return Number(value.toFixed(2))
}

/**
 * 购物车
 * 对应小程序 Vuex 的 `orderListData` + 首页的 `getTableOrderDishListes` / `computOrderInfo`
 */
export const useCartStore = defineStore('cart', () => {
  /** 对应 Vuex `orderListData`：接口原样返回的购物车数组 */
  const items = ref<ShoppingCartItem[]>([])
  const loading = ref(false)

  /** 菜品总件数（源码 `orderDishNumber`） */
  const totalCount = computed(() => items.value.reduce((sum, n) => sum + Number(n.number ?? 0), 0))

  /** 菜品总金额（分，源码 `orderDishPrice` 的菜品部分，未含配送费/打包费） */
  const totalFen = computed(() =>
    items.value.reduce((sum, n) => sum + Number(n.number ?? 0) * Number(n.amount ?? 0), 0),
  )

  /** 去结算栏展示的金额（元） */
  const totalYuan = computed(() => fenToYuan(totalFen.value))

  const isEmpty = computed(() => items.value.length === 0)

  /** 按 id 找购物车中对应菜品/套餐的数量；用于列表项回填 dishNumber */
  function numberById(id?: number, setmealId?: number): number {
    const hit = items.value.find(
      (n) =>
        (id !== undefined && Number(n.dishId) === Number(id)) ||
        (setmealId !== undefined && Number(n.setmealId) === Number(setmealId)),
    )
    return Number(hit?.number ?? 0)
  }

  /** 拉取购物车列表，对齐源码 `getTableOrderDishListes` */
  async function fetchCart() {
    loading.value = true
    try {
      const res = await getShoppingCartList()
      if (res.code === SUCCESS_CODE) {
        items.value = (res.data ?? []) as ShoppingCartItem[]
      }
      return res
    } catch {
      // 请求层已统一提示，这里保持购物车原状
      return undefined
    } finally {
      loading.value = false
    }
  }

  /** 加菜 */
  async function addItem(params: ShoppingCartDTO) {
    const res = await addShoppingCart(params)
    if (res.code === SUCCESS_CODE) await fetchCart()
    return res
  }

  /** 减菜 */
  async function subItem(params: ShoppingCartDTO) {
    const res = await subShoppingCart(params)
    if (res.code === SUCCESS_CODE) await fetchCart()
    return res
  }

  /** 清空购物车 */
  async function clear() {
    try {
      await cleanShoppingCart()
    } finally {
      await fetchCart()
    }
  }

  /**
   * 把购物车数量回填到菜品列表项上（源码 `setOrderNum`）。
   * 顺带剔除 `flavors[]` 里 `name === ''` 的空规格项（源码如此）。
   */
  function decorateDishNumbers<T extends { id?: number; flavors?: { name?: string }[] }>(
    list: T[],
  ): (T & { dishNumber: number })[] {
    return list.map((dish) => {
      if (Array.isArray(dish.flavors)) {
        dish.flavors = dish.flavors.filter((f) => f.name !== '')
      }
      return { ...dish, dishNumber: numberById(dish.id) }
    })
  }

  return {
    items,
    loading,
    totalCount,
    totalFen,
    totalYuan,
    isEmpty,
    fetchCart,
    addItem,
    subItem,
    clear,
    numberById,
    decorateDishNumbers,
  }
})
