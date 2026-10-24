<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDiningPreferences, type DiningPreference } from '@/api/modules/user'
import DishCard from '@/components/DishCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getAddressBookDefault } from '@/api/modules/address'
import {
  dishCategoryList,
  dishListByCategoryId,
  setmealDishList,
  setmealListByCategoryId,
} from '@/api/modules/dish'
import { DELIVERY_FEE, getImageUrl } from '@/api/request'
import { SUCCESS_CODE } from '@/constants/business'
import { listDishReviews, type DishReview } from '@/api/modules/meal'
import type { AddressBook, Dish, DishItem, Setmeal, SourceDishCategory } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'

type DishRow = (Dish | Setmeal) & {
  type: number
  categoryId: number
  categoryName: string
}

const router = useRouter()
const route = useRoute()
const cart = useCartStore()
const checkout = useCheckoutStore()
const shop = useShopStore()

const diningPreference = ref<DiningPreference | null>(null)
const categories = ref<SourceDishCategory[]>([])
const activeCategoryId = ref<number | 'all'>('all')
const loadedCategoryIds = ref<number[]>([])
const productsByCategory = ref<Record<number, DishRow[]>>({})
const loadingDish = ref(false)
const searchText = ref('')
const deliveryAddress = ref<AddressBook | null>(checkout.address)
const filterOpen = ref(false)

const flavorVisible = ref(false)
const flavorDish = ref<DishRow | null>(null)
const flavorGroups = ref<{ name: string; value: string[] }[]>([])
const selectedFlavors = ref<string[]>([])

const detailVisible = ref(false)
const detailDish = ref<DishRow | null>(null)
const detailTransitionBusy = ref(false)
const sharedTransitionDishKey = ref('')
let detailOriginElement: HTMLElement | null = null
const setmealDishes = ref<DishItem[]>([])
const dishReviews = ref<DishReview[]>([])
const dishReviewPage = ref(1)
const hasMoreDishReviews = ref(false)
const cartVisible = ref(false)
const activeBannerIndex = ref(0)
const bannerPaused = ref(false)
const recommendationTrack = ref<HTMLElement | null>(null)
const recommendationPaused = ref(false)
let bannerTimer: number | undefined
let recommendationTimer: number | undefined

type ViewTransitionDocument = Document & {
  startViewTransition?: (callback: () => void | Promise<void>) => {
    updateCallbackDone: Promise<void>
  }
}

function dishTransitionKey(row: DishRow): string {
  return `${row.type}-${row.id}`
}

async function commitViewTransition(update: () => void) {
  const startViewTransition = (document as ViewTransitionDocument).startViewTransition
  if (!startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    update()
    await nextTick()
    return
  }
  const transition = startViewTransition.call(document, async () => {
    update()
    await nextTick()
  })
  await transition.updateCallbackDone
}

const campaignBanners = [
  {
    eyebrow: 'FRESHLY PREPARED · JUST FOR YOU',
    title: '好食材，认真做好每一餐',
    description: '挑选喜欢的菜品，现点现做，让每一餐都更有滋味。',
    action: '开始点餐',
    tone: 'mint',
  },
  {
    eyebrow: 'A GOOD MEAL, A GOOD MOOD',
    title: '今天想吃点什么？',
    description: '从热菜到主食，看看为你精选的餐桌灵感。',
    action: '看看推荐',
    tone: 'lime',
  },
  {
    eyebrow: 'MADE WITH CARE',
    title: '忙碌一天，也要好好吃饭',
    description: '选好美味加入购物车，轻松安排下一餐。',
    action: '去逛菜单',
    tone: 'sage',
  },
]

const allDishRows = computed(() =>
  categories.value.flatMap((category) => productsByCategory.value[Number(category.id)] ?? []),
)

const isOrderingPage = computed(() => route.hash === '#menu')

function tasteMatchScore(row: DishRow): number {
  const searchableText = [row.name, row.description, row.categoryName]
    .map((value) => String(value ?? '').toLocaleLowerCase())
    .join(' ')
  return (diningPreference.value?.tastes ?? []).reduce((score, taste) => {
    const keywords: Record<string, string[]> = {
      清淡: ['清淡', '蒸', '煮', '白灼'],
      微辣: ['微辣', '小辣'],
      中辣: ['中辣', '香辣'],
      重辣: ['重辣', '特辣', '麻辣'],
      甜口: ['甜', '糖醋', '蜜汁'],
      少油: ['少油', '清蒸', '白灼'],
      素食: ['素', '蔬菜', '菌菇', '豆腐'],
    }
    const matched = (keywords[taste] ?? [taste]).some((keyword) =>
      searchableText.includes(keyword.toLocaleLowerCase()),
    )
    return score + Number(matched)
  }, 0)
}

const recommendedRows = computed(() => {
  const preference = diningPreference.value
  if (preference?.dailyBudget !== null && preference?.dailyBudget !== undefined) {
    const remainingBudget = Number(preference.remainingBudget ?? 0)
    const budgetRows = allDishRows.value.filter(
      (row) => fenToYuan(Number(row.price ?? 0)) <= remainingBudget,
    )
    return [...budgetRows]
      .sort((left, right) => tasteMatchScore(right) - tasteMatchScore(left))
      .slice(0, 12)
  }
  return (() => {
    const firstFromEachCategory = categories.value
      .map((category) => productsByCategory.value[categoryKey(category)]?.[0])
      .filter((row): row is DishRow => Boolean(row))
    const selectedIds = new Set(
      firstFromEachCategory.map((row) => `${row.type}-${row.id}`),
    )
    const remainingRows = allDishRows.value.filter(
      (row) => !selectedIds.has(`${row.type}-${row.id}`),
    )
    return [...firstFromEachCategory, ...remainingRows].slice(0, 12)
  })()
})

function hasTasteMatch(row: DishRow): boolean {
  return tasteMatchScore(row) > 0
}

function openDiningPreferences() {
  void router.push('/dining-preferences')
}

async function loadDiningPreferences() {
  try {
    const response = await getDiningPreferences()
    if (response.code !== SUCCESS_CODE || !response.data) {
      throw new Error(response.msg || '读取口味与预算失败')
    }
    diningPreference.value = response.data
  } catch {
    diningPreference.value = null
    ElMessage.warning('读取口味与预算失败，请确认已部署偏好接口并执行数据库迁移')
  }
}

const visibleRows = computed(() => {
  const rows =
    activeCategoryId.value === 'all'
      ? allDishRows.value
      : productsByCategory.value[activeCategoryId.value] ?? []
  const keyword = searchText.value.trim().toLocaleLowerCase()
  if (!keyword) return rows
  return rows.filter((row) =>
    [row.name, row.description, row.categoryName].some((value) =>
      String(value ?? '').toLocaleLowerCase().includes(keyword),
    ),
  )
})

const selectedCategoryName = computed(
  () =>
    categories.value.find((category) => Number(category.id) === activeCategoryId.value)?.name ??
    '全部分类',
)
const deliveryAddressLabel = computed(() => {
  if (!deliveryAddress.value) return '选择收货地址'
  const parts = [
    deliveryAddress.value.provinceName,
    deliveryAddress.value.cityName,
    deliveryAddress.value.districtName,
    deliveryAddress.value.detail,
  ].map((part) => String(part ?? '').trim())
  return parts.filter((part, index) => part && part !== parts[index - 1]).join('')
})
const packAmount = computed(() => cart.totalCount)
const potentialTotal = computed(() => cart.totalYuan + packAmount.value + DELIVERY_FEE)

function categoryKey(category: SourceDishCategory): number {
  return Number(category.id)
}

function categoryItemCount(category: SourceDishCategory): number {
  return productsByCategory.value[categoryKey(category)]?.length ?? 0
}

function countOf(row: DishRow): number {
  return row.type === 2
    ? cart.numberById(undefined, Number(row.id))
    : cart.numberById(Number(row.id))
}

function hasFlavor(row: DishRow): boolean {
  return row.type === 1 && Array.isArray((row as Dish).flavors) && (row as Dish).flavors!.length > 0
}

function priceText(row: DishRow): string {
  return fenToYuan(Number(row.price ?? 0)).toFixed(2)
}

function isNewProduct(row: DishRow): boolean {
  if (!row.updateTime) return false
  const updatedAt = Date.parse(row.updateTime)
  const age = Date.now() - updatedAt
  return Number.isFinite(updatedAt) && age >= 0 && age <= 7 * 24 * 60 * 60 * 1000
}

async function loadCategory(category: SourceDishCategory) {
  const id = categoryKey(category)
  if (!Number.isFinite(id) || loadedCategoryIds.value.includes(id)) return

  try {
    if (Number(category.type) === 1) {
      const res = await dishListByCategoryId({ categoryId: id })
      productsByCategory.value[id] =
        res.code === SUCCESS_CODE
          ? ((res.data ?? []) as Dish[]).map((dish) => ({
              ...dish,
              type: 1,
              categoryId: id,
              categoryName: String(category.name ?? ''),
            }))
          : []
    } else {
      const res = await setmealListByCategoryId({ categoryId: id })
      productsByCategory.value[id] =
        res.code === SUCCESS_CODE
          ? ((res.data ?? []) as Setmeal[]).map((setmeal) => ({
              ...setmeal,
              type: 2,
              categoryId: id,
              categoryName: String(category.name ?? ''),
            }))
          : []
    }
  } catch {
    productsByCategory.value[id] = []
  } finally {
    loadedCategoryIds.value = [...loadedCategoryIds.value, id]
  }
}

async function loadCategories() {
  try {
    const res = await dishCategoryList()
    if (res.code !== SUCCESS_CODE) return
    categories.value = (res.data ?? []).filter(
      (category): category is SourceDishCategory =>
        category.id !== undefined && Number.isFinite(Number(category.id)),
    )
    loadingDish.value = true
    await Promise.all(categories.value.map(loadCategory))
  } catch {
    categories.value = []
  } finally {
    loadingDish.value = false
  }
}

async function selectCategory(id: number | 'all') {
  activeCategoryId.value = id
  filterOpen.value = false
  if (id === 'all') return
  const category = categories.value.find((item) => categoryKey(item) === id)
  if (category) {
    loadingDish.value = true
    await loadCategory(category)
    loadingDish.value = false
  }
}

async function goOrdering() {
  await router.push({ path: '/index', hash: '#menu' })
}

function setActiveBanner(index: number) {
  activeBannerIndex.value = (index + campaignBanners.length) % campaignBanners.length
}

function moveBanner(direction: number) {
  setActiveBanner(activeBannerIndex.value + direction)
}

function scrollRecommendations(direction: number) {
  const track = recommendationTrack.value
  if (!track) return
  const card = track.querySelector<HTMLElement>('.recommendation-card')
  const gap = Number.parseFloat(getComputedStyle(track).columnGap) || 18
  const step = card ? card.getBoundingClientRect().width + gap : track.clientWidth * 0.7
  const atEnd = track.scrollLeft + track.clientWidth >= track.scrollWidth - 8
  if (direction > 0 && atEnd) {
    track.scrollTo({ left: 0, behavior: 'smooth' })
    return
  }
  track.scrollBy({ left: step * direction, behavior: 'smooth' })
}

function goAddress() {
  checkout.setAddressBackPath('/index')
  router.push('/address')
}

async function loadDeliveryAddress() {
  if (checkout.address) {
    deliveryAddress.value = checkout.address
    return
  }
  try {
    const res = await getAddressBookDefault()
    if (res.code === SUCCESS_CODE && res.data) {
      const address = res.data as AddressBook
      deliveryAddress.value = address
      checkout.setAddress(address)
    }
  } catch {
    deliveryAddress.value = null
  }
}

async function submitOrder() {
  if (shop.isClosed) {
    ElMessage.warning('本店已打烊，暂不能下单')
    return
  }
  if (!cart.isEmpty) {
    cartVisible.value = false
    await router.push('/order')
  }
}

async function addRow(row: DishRow, flavor: string | null) {
  try {
    if (row.type === 2) await cart.addItem({ setmealId: Number(row.id) })
    else await cart.addItem({ dishId: Number(row.id), dishFlavor: flavor })
  } catch {
    // 请求层已提示
  }
}

async function quickAdd(row: DishRow) {
  if (shop.isClosed) {
    ElMessage.warning('本店已打烊，暂不能下单')
    return
  }
  if (hasFlavor(row)) {
    openFlavorDialog(row)
    return
  }
  await addRow(row, null)
}

async function quickReduce(row: DishRow) {
  try {
    if (row.type === 2) {
      await cart.subItem({ setmealId: Number(row.id) })
      return
    }
    const dish = row as Dish
    await cart.subItem({ dishId: Number(row.id), dishFlavor: firstFlavorOf(dish) })
  } catch {
    // 请求层已提示
  }
}

function firstFlavorOf(dish: Dish): string | null {
  if (dish.dishFlavor) return dish.dishFlavor
  const remark = parseFlavorRemark(dish.flavorRemark)
  return remark[0] ?? null
}

function parseFlavorRemark(remark?: string): string[] {
  if (!remark) return []
  try {
    const parsed: unknown = JSON.parse(remark)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function parseFlavorValue(value?: string): string[] {
  if (!value) return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map(String) : [String(parsed)]
  } catch {
    return [value]
  }
}

function openFlavorDialog(row: DishRow) {
  if (shop.isClosed) {
    ElMessage.warning('本店已打烊，暂不能下单')
    return
  }
  const dish = row as Dish
  flavorDish.value = row
  flavorGroups.value = (dish.flavors ?? [])
    .filter((flavor) => flavor.name !== '')
    .map((flavor) => ({
      name: String(flavor.name ?? ''),
      value: parseFlavorValue(flavor.value),
    }))
  selectedFlavors.value = flavorGroups.value.map((group) => group.value[0] ?? '')
  flavorVisible.value = true
}

function pickFlavor(groupIndex: number, value: string) {
  const next = [...selectedFlavors.value]
  next[groupIndex] = value
  selectedFlavors.value = next
}

async function confirmFlavor() {
  const row = flavorDish.value
  if (!row) return
  if (flavorGroups.value.length > 0 && selectedFlavors.value.every((value) => !value)) {
    ElMessage.warning('请选择规格')
    return
  }
  const flavor = selectedFlavors.value.filter(Boolean).join(',') || null
  ;(row as Dish).flavorRemark = JSON.stringify(selectedFlavors.value)
  flavorVisible.value = false
  await addRow(row, flavor)
}

async function openDetail(row: DishRow) {
  if (detailTransitionBusy.value || detailVisible.value) return
  detailTransitionBusy.value = true
  const transitionKey = dishTransitionKey(row)
  sharedTransitionDishKey.value = transitionKey
  await nextTick()
  await commitViewTransition(() => {
    detailDish.value = row
    detailVisible.value = true
    sharedTransitionDishKey.value = ''
  })
  detailOriginElement = document.querySelector<HTMLElement>(
    `[data-dish-transition-key="${transitionKey}"]`,
  )
  detailOriginElement =
    detailOriginElement?.querySelector<HTMLElement>('button') ?? detailOriginElement
  detailTransitionBusy.value = false
  setmealDishes.value = []
  dishReviews.value = []
  dishReviewPage.value = 1
  hasMoreDishReviews.value = false
  if (row.type === 1) {
    try {
      const response = await listDishReviews(Number(row.id), dishReviewPage.value, 3)
      if (response.code === SUCCESS_CODE) {
        dishReviews.value = response.data ?? []
        hasMoreDishReviews.value = dishReviews.value.length === 3
      }
    } catch {
      // 请求层已提示
    }
    return
  }

  try {
    const res = await setmealDishList(Number(row.id))
    if (res.code === SUCCESS_CODE) setmealDishes.value = (res.data ?? []) as DishItem[]
  } catch {
    setmealDishes.value = []
  }
}

async function closeDetail() {
  const row = detailDish.value
  if (!row || detailTransitionBusy.value) return
  detailTransitionBusy.value = true
  const transitionKey = dishTransitionKey(row)
  await commitViewTransition(() => {
    detailVisible.value = false
    detailDish.value = null
    sharedTransitionDishKey.value = transitionKey
  })
  detailOriginElement?.focus()
  detailOriginElement = null
  detailTransitionBusy.value = false
}

function handleDetailKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && detailVisible.value) void closeDetail()
}

async function loadMoreDishReviews() {
  const row = detailDish.value
  if (!row || row.type !== 1 || !hasMoreDishReviews.value) return
  const nextPage = dishReviewPage.value + 1
  const response = await listDishReviews(Number(row.id), nextPage, 3)
  if (response.code === SUCCESS_CODE) {
    const nextReviews = response.data ?? []
    dishReviews.value = [...dishReviews.value, ...nextReviews]
    dishReviewPage.value = nextPage
    hasMoreDishReviews.value = nextReviews.length === 3
  }
}

async function addFromDetail() {
  const row = detailDish.value
  if (!row) return
  await closeDetail()
  await quickAdd(row)
}

async function addCartItem(item: (typeof cart.items)[number]) {
  try {
    if (item.setmealId !== undefined && item.setmealId !== null) {
      await cart.addItem({ setmealId: Number(item.setmealId) })
    } else if (item.dishId !== undefined && item.dishId !== null) {
      await cart.addItem({ dishId: Number(item.dishId), dishFlavor: item.dishFlavor ?? null })
    }
  } catch {
    // 请求层已提示
  }
}

async function reduceCartItem(item: (typeof cart.items)[number]) {
  try {
    if (item.setmealId !== undefined && item.setmealId !== null) {
      await cart.subItem({ setmealId: Number(item.setmealId) })
    } else if (item.dishId !== undefined && item.dishId !== null) {
      await cart.subItem({ dishId: Number(item.dishId), dishFlavor: item.dishFlavor ?? null })
    }
  } catch {
    // 请求层已提示
  }
}

async function clearCart() {
  try {
    await cart.clear()
  } catch {
    // 请求层已提示
  }
}

onMounted(async () => {
  window.addEventListener('keydown', handleDetailKeydown)
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!reduceMotion) {
    bannerTimer = window.setInterval(() => {
      if (!bannerPaused.value && !isOrderingPage.value) moveBanner(1)
    }, 6000)
    recommendationTimer = window.setInterval(() => {
      if (
        !recommendationPaused.value &&
        !isOrderingPage.value &&
        recommendedRows.value.length > 1
      ) {
        scrollRecommendations(1)
      }
    }, 4500)
  }
  await Promise.all([shop.fetchAll(), cart.fetchCart(), loadDeliveryAddress()])
  await Promise.all([loadCategories(), loadDiningPreferences()])
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleDetailKeydown)
  if (bannerTimer !== undefined) window.clearInterval(bannerTimer)
  if (recommendationTimer !== undefined) window.clearInterval(recommendationTimer)
})
</script>

<template>
  <div class="storefront">
    <section
      v-if="!isOrderingPage"
      class="campaign-banner"
      :class="`is-${campaignBanners[activeBannerIndex].tone}`"
      aria-label="活动推荐"
      @mouseenter="bannerPaused = true"
      @mouseleave="bannerPaused = false"
      @focusin="bannerPaused = true"
      @focusout="bannerPaused = false"
    >
      <div class="campaign-copy">
        <p class="campaign-eyebrow">{{ campaignBanners[activeBannerIndex].eyebrow }}</p>
        <h1>{{ campaignBanners[activeBannerIndex].title }}</h1>
        <p class="campaign-description">{{ campaignBanners[activeBannerIndex].description }}</p>
        <button class="campaign-action" type="button" @click="goOrdering">
          {{ campaignBanners[activeBannerIndex].action }}
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </div>
      <div class="campaign-art" aria-hidden="true">
        <span class="campaign-orbit orbit-one" />
        <span class="campaign-orbit orbit-two" />
        <span class="campaign-plate"><el-icon><Dish /></el-icon></span>
        <span class="campaign-leaf leaf-one" />
        <span class="campaign-leaf leaf-two" />
        <span class="campaign-note">FRESH<br />& DAILY</span>
      </div>
      <div class="campaign-controls">
        <button type="button" aria-label="上一条活动" @click="moveBanner(-1)">
          <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
        </button>
        <button type="button" aria-label="下一条活动" @click="moveBanner(1)">
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </div>
      <div class="campaign-indicators" role="group" aria-label="选择活动">
        <button
          v-for="(banner, index) in campaignBanners"
          :key="banner.title"
          type="button"
          :class="{ active: activeBannerIndex === index }"
          :aria-label="`第 ${index + 1} 条活动`"
          :aria-pressed="activeBannerIndex === index"
          @click="setActiveBanner(index)"
        />
      </div>
    </section>

    <section v-if="isOrderingPage" class="store-toolbar" aria-label="配送、搜索和购物车">
      <button class="delivery-address" type="button" @click="goAddress">
        <el-icon aria-hidden="true"><Location /></el-icon>
        <span class="delivery-copy">
          <small>配送至</small>
          <strong>
            {{
              deliveryAddressLabel
            }}
          </strong>
        </span>
        <el-icon class="address-chevron" aria-hidden="true"><ArrowDown /></el-icon>
      </button>

      <span class="delivery-mode">
        <el-icon aria-hidden="true"><Van /></el-icon>
        外卖配送
      </span>

      <el-input
        v-model="searchText"
        class="menu-search"
        clearable
        aria-label="搜索菜品"
        placeholder="搜索菜品、套餐"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <div class="filter-wrap">
        <button
          class="filter-button"
          type="button"
          :aria-expanded="filterOpen"
          @click="filterOpen = !filterOpen"
        >
          <el-icon aria-hidden="true"><Filter /></el-icon>
          <span>{{ selectedCategoryName }}</span>
          <el-icon class="filter-chevron" aria-hidden="true"><ArrowDown /></el-icon>
        </button>
        <div v-if="filterOpen" class="filter-menu" role="group" aria-label="按分类筛选">
          <button
            class="filter-option"
            :class="{ selected: activeCategoryId === 'all' }"
            type="button"
            @click="selectCategory('all')"
          >
            全部分类
          </button>
          <button
            v-for="category in categories"
            :key="category.id ?? category.name"
            class="filter-option"
            :class="{ selected: activeCategoryId === categoryKey(category) }"
            type="button"
            @click="selectCategory(categoryKey(category))"
          >
            {{ category.name }}
            <span>{{ categoryItemCount(category) }}</span>
          </button>
        </div>
      </div>

      <button class="toolbar-cart" type="button" aria-label="打开购物车" @click="cartVisible = true">
        <el-icon aria-hidden="true"><ShoppingCart /></el-icon>
        <span>购物车</span>
        <b v-if="cart.totalCount" class="cart-badge">{{ cart.totalCount }}</b>
      </button>
    </section>

    <section
      v-if="!isOrderingPage"
      class="recommendations-section"
      aria-labelledby="recommendations-title"
    >
      <div class="section-heading">
        <div>
          <p class="section-eyebrow">SMART PICKS · MADE FRESH</p>
          <h2 id="recommendations-title">口味与预算推荐</h2>
          <p class="menu-subtitle">
            {{
              diningPreference?.dailyBudget == null
                ? '设置口味和每日限额，发现更适合你的菜品'
                : `今日已付 ￥${Number(diningPreference.spentToday).toFixed(2)}，按剩余额度为你筛选`
            }}
          </p>
        </div>
        <div class="recommendation-heading-actions">
          <button class="browse-menu-button" type="button" @click="openDiningPreferences">
            设置偏好
            <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          </button>
          <button
            class="carousel-control"
            type="button"
            aria-label="上一组推荐菜品"
            :disabled="recommendedRows.length < 2"
            @click="scrollRecommendations(-1)"
          >
            <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
          </button>
          <button
            class="carousel-control"
            type="button"
            aria-label="下一组推荐菜品"
            :disabled="recommendedRows.length < 2"
            @click="scrollRecommendations(1)"
          >
            <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          </button>
          <button class="browse-menu-button" type="button" @click="goOrdering">
            全部菜品
            <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          </button>
        </div>
      </div>

      <div
        v-loading="loadingDish"
        class="recommendation-results"
        @mouseenter="recommendationPaused = true"
        @mouseleave="recommendationPaused = false"
        @focusin="recommendationPaused = true"
        @focusout="recommendationPaused = false"
      >
        <div
          v-if="recommendedRows.length"
          ref="recommendationTrack"
          class="recommendation-track"
          aria-label="推荐菜品，可横向滑动"
        >
          <article
            v-for="row in recommendedRows"
            :key="`recommend-${row.type}-${row.id}`"
            class="recommendation-card"
            :data-dish-transition-key="dishTransitionKey(row)"
            :style="{
              viewTransitionName:
                sharedTransitionDishKey === dishTransitionKey(row)
                  ? 'dish-detail-shared'
                  : undefined,
            }"
          >
            <button class="recommendation-image" type="button" @click="openDetail(row)">
              <img
                v-if="getImageUrl(row.image)"
                v-image-fallback
                :src="getImageUrl(row.image)"
                :alt="String(row.name ?? '')"
                loading="lazy"
              />
              <span v-else class="recommendation-image-placeholder">
                <el-icon aria-hidden="true"><Dish /></el-icon>
                <span>本店现做</span>
              </span>
              <span class="recommendation-badge">
                {{ hasTasteMatch(row) ? '口味匹配' : isNewProduct(row) ? '新品' : '预算内精选' }}
              </span>
            </button>
            <div class="recommendation-card-body">
              <div class="recommendation-card-heading">
                <div>
                  <span class="recommendation-category">{{ row.categoryName }}</span>
                  <h3>{{ row.name }}</h3>
                </div>
                <span class="recommendation-sales">销量统计待接入</span>
              </div>
              <p class="recommendation-description">{{ row.description || '本店用心制作，现点现做' }}</p>
              <div class="recommendation-card-footer">
                <p class="recommendation-price">
                  <small>￥</small>{{ priceText(row) }}
                </p>
                <button
                  class="recommendation-add"
                  type="button"
                  :disabled="shop.isClosed || cart.loading"
                  @click="hasFlavor(row) ? openFlavorDialog(row) : quickAdd(row)"
                >
                  <el-icon aria-hidden="true"><Plus /></el-icon>
                  {{ hasFlavor(row) ? '选规格' : countOf(row) ? `已选 ${countOf(row)}` : '加购' }}
                </button>
              </div>
            </div>
          </article>
        </div>
        <EmptyState
          v-else-if="!loadingDish"
          :text="
            diningPreference?.dailyBudget != null && Number(diningPreference.remainingBudget) <= 0
              ? '今日餐费限额已用完'
              : diningPreference?.dailyBudget != null
                ? '当前没有符合剩余额度的菜品'
                : '暂时没有可推荐的菜品'
          "
          image="/static/imgDefault.png"
          :image-size="96"
        />
      </div>
    </section>

    <section v-if="isOrderingPage" id="menu" class="menu-section" aria-labelledby="menu-title">
      <div class="section-heading">
        <div>
          <p class="section-eyebrow">COOKED TO ORDER</p>
          <h2 id="menu-title">店铺菜单</h2>
          <p class="menu-subtitle">菜品、套餐与价格来自店铺实时菜单</p>
        </div>
        <span class="section-count">{{ visibleRows.length }} 款商品</span>
      </div>

      <nav class="category-nav" aria-label="菜品分类">
        <button
          class="category-option"
          :class="{ active: activeCategoryId === 'all' }"
          type="button"
          :aria-pressed="activeCategoryId === 'all'"
          @click="selectCategory('all')"
        >
          全部菜品
        </button>
        <button
          v-for="category in categories"
          :key="category.id ?? category.name"
          class="category-option"
          :class="{ active: activeCategoryId === categoryKey(category) }"
          type="button"
          :aria-pressed="activeCategoryId === categoryKey(category)"
          @click="selectCategory(categoryKey(category))"
        >
          {{ category.name }}
          <span>{{ categoryItemCount(category) }}</span>
        </button>
      </nav>

      <div v-loading="loadingDish" class="menu-results">
        <div v-if="visibleRows.length" class="dish-grid">
          <DishCard
            v-for="(row, index) in visibleRows"
            :key="`${row.type}-${row.id}`"
            class="dish-enter"
            :style="{ '--dish-enter-delay': `${Math.min(index, 7) * 45}ms` }"
            :data-dish-transition-key="dishTransitionKey(row)"
            :name="String(row.name ?? '')"
            :description="String(row.description ?? '')"
            :image="getImageUrl(row.image)"
            :price="Number(row.price ?? 0)"
            :count="countOf(row)"
            :has-flavor="hasFlavor(row)"
            :category="row.categoryName"
            :disabled="shop.isClosed"
            :loading="cart.loading"
            :shared-transition="sharedTransitionDishKey === dishTransitionKey(row)"
            @open-detail="openDetail(row)"
            @choose-flavor="openFlavorDialog(row)"
            @add="quickAdd(row)"
            @reduce="quickReduce(row)"
          />
        </div>
        <EmptyState
          v-else-if="!loadingDish"
          :text="searchText ? '没有找到匹配的商品' : '当前分类暂无在售商品'"
          image="/static/imgDefault.png"
          :image-size="96"
        />
      </div>
    </section>

    <footer class="cart-dock" aria-label="购物车结算">
      <button class="cart-overview" type="button" @click="cartVisible = true">
        <span class="cart-icon">
          <el-icon aria-hidden="true"><ShoppingCart /></el-icon>
          <b v-if="cart.totalCount">{{ cart.totalCount }}</b>
        </span>
        <span class="cart-copy">
          <strong>{{ cart.isEmpty ? '购物车暂时为空' : `已选 ${cart.totalCount} 件商品` }}</strong>
          <small>{{ cart.isEmpty ? '选几道喜欢的菜吧' : '含打包费与配送费' }}</small>
        </span>
        <strong class="cart-total">￥{{ cart.isEmpty ? '0.00' : potentialTotal.toFixed(2) }}</strong>
      </button>
      <button
        class="checkout-button"
        type="button"
        :disabled="cart.isEmpty || shop.isClosed || cart.loading"
        @click="submitOrder"
      >
        去结算
      </button>
    </footer>

    <el-drawer v-model="cartVisible" title="购物车" direction="rtl" size="min(440px, 100vw)">
      <div class="cart-drawer">
        <div v-if="cart.isEmpty" class="cart-empty">
          <el-icon><ShoppingCart /></el-icon>
          <p>购物车还是空的</p>
          <span>回到菜单挑选喜欢的菜品</span>
        </div>
        <template v-else>
          <div class="drawer-items">
            <article v-for="item in cart.items" :key="item.id" class="drawer-item">
              <img
                v-if="getImageUrl(item.image)"
                v-image-fallback
                :src="getImageUrl(item.image)"
                :alt="String(item.name ?? '')"
              />
              <div v-else class="drawer-item-placeholder" aria-hidden="true">
                {{ String(item.name ?? '').slice(0, 1) }}
              </div>
              <div class="drawer-item-info">
                <strong>{{ item.name }}</strong>
                <small v-if="item.dishFlavor">{{ item.dishFlavor }}</small>
                <span>￥{{ fenToYuan(Number(item.amount ?? 0)).toFixed(2) }}</span>
              </div>
              <div class="drawer-stepper">
                <button
                  type="button"
                  :aria-label="`减少${item.name}`"
                  :disabled="cart.loading"
                  @click="reduceCartItem(item)"
                >
                  −
                </button>
                <span>{{ item.number }}</span>
                <button
                  type="button"
                  :aria-label="`增加${item.name}`"
                  :disabled="shop.isClosed || cart.loading"
                  @click="addCartItem(item)"
                >
                  +
                </button>
              </div>
            </article>
          </div>
          <div class="drawer-summary">
            <div><span>菜品金额</span><span>￥{{ cart.totalYuan.toFixed(2) }}</span></div>
            <div><span>打包费</span><span>￥{{ packAmount.toFixed(2) }}</span></div>
            <div><span>配送费</span><span>￥{{ DELIVERY_FEE.toFixed(2) }}</span></div>
            <div class="drawer-grand-total">
              <strong>合计</strong><strong>￥{{ potentialTotal.toFixed(2) }}</strong>
            </div>
            <div class="drawer-actions">
              <button
                class="clear-cart-button"
                type="button"
                :disabled="cart.loading"
                @click="clearCart"
              >
                清空购物车
              </button>
              <button
                class="checkout-button"
                type="button"
                :disabled="shop.isClosed || cart.loading"
                @click="submitOrder"
              >
                去结算
              </button>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="flavorVisible" :title="String(flavorDish?.name ?? '')" width="520px">
      <div v-for="(group, index) in flavorGroups" :key="group.name" class="flavor-group">
        <p>{{ group.name }}</p>
        <div class="flavor-options">
          <button
            v-for="value in group.value"
            :key="value"
            type="button"
            :class="{ selected: selectedFlavors[index] === value }"
            @click="pickFlavor(index, value)"
          >
            {{ value }}
          </button>
        </div>
      </div>
      <div class="dialog-footer">
        <strong>￥{{ fenToYuan(Number(flavorDish?.price ?? 0)).toFixed(2) }}</strong>
        <button
          class="checkout-button"
          type="button"
          :disabled="shop.isClosed || cart.loading"
          @click="confirmFlavor"
        >
          加入购物车
        </button>
      </div>
    </el-dialog>

    <Teleport to="body">
      <section
        v-if="detailVisible && detailDish"
        class="dish-detail-page"
        aria-labelledby="dish-detail-title"
        :style="{ viewTransitionName: 'dish-detail-shared' }"
      >
        <div class="dish-detail-shell">
          <header class="dish-detail-header">
            <button class="detail-back-button" type="button" @click="closeDetail">
              <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
              <span>返回菜单</span>
            </button>
            <span class="detail-category">{{ detailDish.categoryName }}</span>
          </header>
          <div class="dish-detail-scroll">
            <article class="dish-detail-content">
              <img
                v-if="getImageUrl(detailDish.image)"
                v-image-fallback
                class="dish-detail-hero"
                :src="getImageUrl(detailDish.image)"
                :alt="String(detailDish.name ?? '')"
              />
              <div v-else class="dish-detail-hero dish-detail-placeholder">
                {{ String(detailDish.name ?? '').slice(0, 2) }}
              </div>
              <div class="dish-detail-copy">
                <p class="detail-eyebrow">{{ detailDish.type === 2 ? '精选套餐' : '现点现做' }}</p>
                <h1 id="dish-detail-title">{{ detailDish.name }}</h1>
                <p class="dish-detail-description">
                  {{ detailDish.description || '本店现做' }}
                </p>
                <div v-if="detailDish.type === 2" class="setmeal-contents">
                  <h3>套餐包含</h3>
                  <div v-for="(dish, index) in setmealDishes" :key="index">
                    <span>{{ dish.name }} × {{ dish.copies }}</span>
                    <small>{{ dish.description }}</small>
                  </div>
                </div>
                <section v-else class="dish-review-preview">
                  <div class="review-preview-heading">
                    <h3>用户评价</h3>
                    <span>{{ dishReviews.length }} 条评价</span>
                  </div>
                  <article
                    v-for="review in dishReviews"
                    :key="review.reviewId"
                    class="review-preview-card"
                  >
                    <div>
                      <strong>{{ review.userName }}</strong>
                      <el-rate :model-value="review.rating" disabled size="small" />
                    </div>
                    <p>{{ review.content || '用户暂未填写文字评价' }}</p>
                    <div v-if="review.images?.length" class="review-preview-images">
                      <img
                        v-for="image in review.images"
                        :key="image.imageUrl"
                        :src="getImageUrl(image.imageUrl)"
                        alt="评价图片"
                      />
                    </div>
                  </article>
                  <el-empty
                    v-if="dishReviews.length === 0"
                    description="暂无菜品评价"
                    :image-size="54"
                  />
                  <el-button
                    v-if="hasMoreDishReviews"
                    class="more-reviews-button"
                    text
                    @click="loadMoreDishReviews"
                  >
                    加载更多评价
                  </el-button>
                </section>
              </div>
            </article>
          </div>
          <footer class="dish-detail-footer">
            <strong>￥{{ priceText(detailDish) }}</strong>
            <button
              class="checkout-button"
              type="button"
              :disabled="shop.isClosed || cart.loading || detailTransitionBusy"
              @click="addFromDetail"
            >
              {{ hasFlavor(detailDish) ? '选择规格' : '加入购物车' }}
            </button>
          </footer>
        </div>
      </section>
    </Teleport>
  </div>
</template>

<style scoped>
.storefront {
  width: 100%;
  max-width: 1320px;
  min-width: 0;
  padding-bottom: 112px;
  margin-inline: auto;
  color: var(--sky-text);
}

.campaign-banner {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 264px;
  padding: 38px 54px;
  overflow: hidden;
  border: 1px solid #d7ece3;
  border-radius: 22px;
  background:
    radial-gradient(ellipse at 80% 24%, rgb(255 255 255 / 68%), transparent 28%),
    linear-gradient(112deg, #e8f6ee 0%, #d9eee2 54%, #f4f4dd 100%);
  box-shadow: 0 12px 32px rgb(27 88 63 / 8%);
  transition: background 400ms ease;
}

.campaign-banner.is-lime {
  border-color: #e5eccb;
  background:
    radial-gradient(ellipse at 80% 24%, rgb(255 255 255 / 68%), transparent 28%),
    linear-gradient(112deg, #f2f5df 0%, #e6f0d4 54%, #d8ecdf 100%);
}

.campaign-banner.is-sage {
  border-color: #d9e9df;
  background:
    radial-gradient(ellipse at 80% 24%, rgb(255 255 255 / 68%), transparent 28%),
    linear-gradient(112deg, #e6f1e9 0%, #d7eae1 54%, #edf1df 100%);
}

.campaign-copy {
  position: relative;
  z-index: 2;
  max-width: min(58%, 620px);
}

.campaign-eyebrow {
  margin-bottom: 12px;
  color: var(--sky-primary);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.14em;
}

.campaign-copy h1 {
  max-width: 18ch;
  color: #174b3d;
  font-size: clamp(28px, 3vw, 42px);
  font-weight: 750;
  letter-spacing: -0.045em;
  line-height: 1.18;
}

.campaign-description {
  max-width: 42ch;
  margin-top: 12px;
  color: #557369;
  font-size: 14px;
  line-height: 1.7;
}

.campaign-action {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 42px;
  padding: 0 18px;
  margin-top: 22px;
  border-radius: var(--sky-radius-pill);
  background: var(--sky-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  transition: background 160ms ease, transform 300ms var(--sky-press-release);
}

.campaign-action:hover {
  background: var(--sky-primary-dark);
  transform: translateY(-1px);
}

.campaign-art {
  position: absolute;
  top: 50%;
  right: 12%;
  width: 330px;
  height: 230px;
  transform: translateY(-50%);
}

.campaign-orbit {
  position: absolute;
  top: 50%;
  left: 50%;
  border: 1px solid rgb(20 125 105 / 15%);
  border-radius: 50%;
  transform: translate(-50%, -50%) rotate(-18deg);
}

.orbit-one {
  width: 278px;
  height: 178px;
}

.orbit-two {
  width: 224px;
  height: 224px;
}

.campaign-plate {
  position: absolute;
  top: 50%;
  left: 50%;
  display: grid;
  width: 142px;
  height: 142px;
  place-items: center;
  border: 10px solid rgb(255 255 255 / 82%);
  border-radius: 50%;
  background: linear-gradient(145deg, #fff 0%, #f7e9ca 100%);
  box-shadow: 0 16px 34px rgb(42 98 73 / 18%), inset 0 0 0 1px #e5ddc8;
  color: #c18e3b;
  font-size: 52px;
  transform: translate(-50%, -50%) rotate(-12deg);
}

.campaign-plate .el-icon {
  filter: drop-shadow(0 4px 3px rgb(117 96 51 / 16%));
}

.campaign-leaf {
  position: absolute;
  width: 36px;
  height: 17px;
  border-radius: 100% 0 100% 0;
  background: #64a884;
  box-shadow: inset -4px -2px 0 rgb(17 93 64 / 12%);
}

.leaf-one {
  top: 25px;
  right: 68px;
  transform: rotate(-22deg);
}

.leaf-two {
  bottom: 28px;
  left: 28px;
  background: #a6bc5d;
  transform: rotate(24deg);
}

.campaign-note {
  position: absolute;
  right: 12px;
  bottom: 26px;
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border: 1px solid rgb(20 125 105 / 24%);
  border-radius: 50%;
  color: var(--sky-primary);
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 0.08em;
  line-height: 1.25;
  text-align: center;
  transform: rotate(10deg);
}

.campaign-controls {
  position: absolute;
  right: 24px;
  bottom: 20px;
  display: flex;
  gap: 7px;
}

.campaign-controls button,
.carousel-control {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgb(20 125 105 / 20%);
  border-radius: 50%;
  background: rgb(255 255 255 / 72%);
  color: var(--sky-primary);
  transition: border-color 160ms ease, background 160ms ease, color 160ms ease,
    transform 300ms var(--sky-press-release);
}

.campaign-controls button:hover,
.carousel-control:hover:not(:disabled) {
  border-color: var(--sky-primary);
  background: var(--sky-primary);
  color: #fff;
}

.campaign-indicators {
  position: absolute;
  bottom: 31px;
  left: 54px;
  display: flex;
  gap: 6px;
}

.campaign-indicators button {
  width: 7px;
  height: 7px;
  padding: 0;
  border-radius: var(--sky-radius-pill);
  background: rgb(20 125 105 / 25%);
  transition: width 180ms ease, background 180ms ease;
}

.campaign-indicators button.active {
  width: 22px;
  background: var(--sky-primary);
}

.store-toolbar {
  position: relative;
  z-index: 10;
  display: grid;
  grid-template-columns: minmax(180px, 1.1fr) auto minmax(220px, 1.3fr) auto auto;
  align-items: center;
  gap: 12px;
  min-height: 66px;
  padding: 10px 14px;
  border: 1px solid var(--sky-border-soft);
  border-radius: 15px;
  background: var(--sky-surface);
  box-shadow: var(--sky-shadow-sm);
}

.delivery-address {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  min-height: 42px;
  padding: 0 4px;
  color: var(--sky-primary);
  text-align: left;
}

.delivery-address > .el-icon {
  flex: 0 0 auto;
  font-size: 19px;
}

.delivery-copy {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.delivery-copy small {
  color: var(--sky-text-weak);
  font-size: 11px;
}

.delivery-copy strong {
  overflow: hidden;
  color: var(--sky-text);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.address-chevron {
  margin-left: auto;
  color: var(--sky-text-weak);
}

.delivery-mode {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 42px;
  padding: 0 11px;
  border-radius: var(--sky-radius);
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.delivery-mode .el-icon {
  font-size: 15px;
}

.menu-search {
  min-width: 0;
}

.menu-search :deep(.el-input__wrapper) {
  min-height: 42px;
  border-radius: var(--sky-radius-pill);
  background: #f8fafc;
  box-shadow: 0 0 0 1px var(--sky-border-soft) inset;
}

.filter-wrap {
  position: relative;
}

.filter-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 150px;
  min-height: 42px;
  padding: 0 11px;
  border: 1px solid var(--sky-border);
  border-radius: var(--sky-radius);
  background: #fff;
  color: var(--sky-text-sub);
  font-size: 12px;
  font-weight: 600;
}

.filter-button:hover,
.filter-button[aria-expanded='true'] {
  border-color: var(--sky-primary);
  color: var(--sky-primary);
}

.filter-button > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter-chevron {
  flex: 0 0 auto;
  margin-left: auto;
}

.filter-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 20;
  width: max-content;
  min-width: 180px;
  max-height: min(360px, 60vh);
  padding: 6px;
  overflow-y: auto;
  border: 1px solid var(--sky-border-soft);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--sky-shadow);
}

.filter-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  width: 100%;
  min-height: 40px;
  padding: 0 10px;
  border-radius: 8px;
  color: var(--sky-text-sub);
  font-size: 13px;
  text-align: left;
}

.filter-option:hover,
.filter-option.selected {
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
}

.filter-option span {
  color: var(--sky-text-weak);
  font-size: 11px;
}

.toolbar-cart {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 48px;
  min-height: 42px;
  padding: 0 13px;
  border-radius: var(--sky-radius-pill);
  background: var(--sky-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  transition: background 160ms ease, transform 300ms var(--sky-press-release);
}

.toolbar-cart:hover {
  background: var(--sky-primary-dark);
  transform: translateY(-1px);
}

.toolbar-cart .el-icon {
  font-size: 17px;
}

.cart-badge {
  position: absolute;
  top: -6px;
  right: -5px;
  display: grid;
  min-width: 19px;
  height: 19px;
  padding: 0 4px;
  place-items: center;
  border: 2px solid #fff;
  border-radius: var(--sky-radius-pill);
  background: #f04438;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

.recommendations-section {
  margin-top: 34px;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 18px;
}

.section-eyebrow {
  margin-bottom: 5px;
  color: var(--sky-primary);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.11em;
}

.section-heading h1,
.section-heading h2 {
  font-size: clamp(22px, 2vw, 26px);
  font-weight: 720;
  letter-spacing: -0.035em;
}

.section-count {
  padding-bottom: 3px;
  color: var(--sky-text-weak);
  font-size: 12px;
  white-space: nowrap;
}

.recommendation-heading-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.carousel-control:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.browse-menu-button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 13px;
  border: 1px solid var(--sky-border);
  border-radius: var(--sky-radius);
  background: var(--sky-surface);
  color: var(--sky-primary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  transition: border-color 160ms ease, background 160ms ease,
    transform 300ms var(--sky-press-release);
}

.browse-menu-button:hover {
  border-color: var(--sky-primary);
  background: var(--sky-primary-soft);
}

.recommendation-results {
  min-height: 350px;
  margin-top: 18px;
  overflow: hidden;
  border-radius: var(--sky-radius-lg);
}

.recommendation-track {
  display: flex;
  gap: 18px;
  width: 100%;
  padding: 2px 2px 14px;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  scroll-behavior: smooth;
  scroll-snap-type: x mandatory;
  scrollbar-width: thin;
  scrollbar-color: var(--sky-border) transparent;
}

.recommendation-card {
  display: flex;
  flex: 0 0 calc((100% - 54px) / 3.5);
  flex-direction: column;
  min-width: 220px;
  overflow: hidden;
  border: 1px solid var(--sky-border-soft);
  border-radius: 17px;
  background: var(--sky-surface);
  box-shadow: 0 4px 16px rgb(31 64 48 / 5%);
  scroll-snap-align: start;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.recommendation-card:hover {
  transform: translateY(-3px);
  border-color: #cfe4d8;
  box-shadow: var(--sky-shadow);
}

.recommendation-image {
  position: relative;
  display: grid;
  width: 100%;
  aspect-ratio: 1.72 / 1;
  min-height: 152px;
  max-height: 208px;
  place-items: center;
  overflow: hidden;
  padding: 0;
  background: linear-gradient(145deg, #edf4ed, #e4eee5 58%, #f4f0e4);
  text-align: center;
}

.recommendation-image > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 260ms ease;
}

.recommendation-card:hover .recommendation-image > img {
  transform: scale(1.04);
}

.recommendation-image-placeholder {
  display: grid;
  gap: 7px;
  place-items: center;
  color: #729080;
  font-size: 12px;
}

.recommendation-image-placeholder .el-icon {
  font-size: 34px;
}

.recommendation-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 5px 9px;
  border: 1px solid rgb(255 255 255 / 60%);
  border-radius: var(--sky-radius-pill);
  background: rgb(19 104 81 / 88%);
  color: #fff;
  font-size: 10px;
  font-weight: 650;
  line-height: 1;
  backdrop-filter: blur(8px);
}

.recommendation-card-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  padding: 15px 16px 16px;
}

.recommendation-card-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 8px;
}

.recommendation-category {
  display: block;
  margin-bottom: 5px;
  color: var(--sky-primary);
  font-size: 10px;
  font-weight: 650;
}

.recommendation-card-heading h3 {
  overflow: hidden;
  color: var(--sky-text);
  font-size: 16px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recommendation-sales {
  flex: 0 0 auto;
  padding-top: 18px;
  color: var(--sky-text-weak);
  font-size: 10px;
  white-space: nowrap;
}

.recommendation-description {
  display: -webkit-box;
  min-height: 39px;
  margin-top: 8px;
  overflow: hidden;
  color: var(--sky-text-sub);
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.recommendation-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 14px;
}

.recommendation-price {
  margin: 0;
  color: var(--sky-price);
  font-size: 21px;
  font-weight: 750;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.recommendation-price small {
  margin-right: 2px;
  font-size: 12px;
  font-weight: 600;
}

.recommendation-add {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 78px;
  min-height: 36px;
  padding: 0 12px;
  border-radius: var(--sky-radius-pill);
  background: var(--sky-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  transition: background 160ms ease, transform 300ms var(--sky-press-release);
}

.recommendation-add:hover:not(:disabled) {
  background: var(--sky-primary-dark);
  transform: translateY(-1px);
}

.recommendation-add:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.menu-section {
  padding-top: 22px;
  scroll-margin-top: 94px;
}

.menu-subtitle {
  margin-top: 5px;
  color: var(--sky-text-sub);
  font-size: 12px;
}

.category-nav {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  padding-bottom: 4px;
  overflow-x: auto;
  scrollbar-width: none;
}

.category-nav::-webkit-scrollbar {
  display: none;
}

.category-option {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  min-height: 35px;
  padding: 0 13px;
  border: 1px solid var(--sky-border);
  border-radius: var(--sky-radius-pill);
  background: #fff;
  color: var(--sky-text-sub);
  font-size: 12px;
  font-weight: 550;
  transition: border-color 160ms ease, background 160ms ease, color 160ms ease;
}

.category-option:hover {
  border-color: var(--sky-primary);
  color: var(--sky-primary);
}

.category-option.active {
  border-color: #f3c590;
  background: var(--sky-primary-soft);
  color: var(--sky-primary-dark);
  font-weight: 650;
}

.category-option span {
  min-width: 16px;
  color: inherit;
  font-size: 10px;
  opacity: 0.76;
  text-align: center;
}

.menu-results {
  min-height: 220px;
  margin-top: 14px;
}

.dish-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 224px), 1fr));
  gap: 18px;
}

.cart-dock {
  position: fixed;
  bottom: max(20px, env(safe-area-inset-bottom));
  left: calc(50% + 116px);
  z-index: 25;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  width: min(920px, calc(100vw - 308px));
  min-height: 72px;
  padding: 9px 11px 9px 15px;
  border: 1px solid var(--sky-border-soft);
  border-radius: 14px;
  background: rgb(255 255 255 / 97%);
  box-shadow: 0 12px 36px rgb(26 38 30 / 15%);
  transform: translateX(-50%);
  backdrop-filter: blur(12px);
}

.cart-overview {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  text-align: left;
}

.cart-icon {
  position: relative;
  display: grid;
  flex: 0 0 42px;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
  font-size: 21px;
}

.cart-icon b {
  position: absolute;
  top: -6px;
  right: -6px;
  display: grid;
  min-width: 19px;
  height: 19px;
  padding: 0 4px;
  place-items: center;
  border: 2px solid #fff;
  border-radius: var(--sky-radius-pill);
  background: #f04438;
  color: #fff;
  font-size: 10px;
}

.cart-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.cart-copy strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cart-copy small {
  color: var(--sky-text-weak);
  font-size: 10px;
}

.cart-total {
  margin-left: auto;
  color: var(--sky-primary);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.checkout-button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 106px;
  min-height: 42px;
  padding: 0 17px;
  border-radius: var(--sky-radius-pill);
  background: var(--sky-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  transition: background 160ms ease, transform 300ms var(--sky-press-release);
}

.checkout-button:hover:not(:disabled) {
  background: var(--sky-primary-dark);
  transform: translateY(-1px);
}

.checkout-button:disabled {
  background: #d7dbd7;
  color: #747b75;
  cursor: not-allowed;
}

.cart-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.cart-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--sky-text-weak);
  text-align: center;
}

.cart-empty .el-icon {
  margin-bottom: 12px;
  font-size: 40px;
}

.cart-empty p {
  color: var(--sky-text);
  font-size: 16px;
  font-weight: 600;
}

.cart-empty span {
  margin-top: 5px;
  font-size: 13px;
}

.drawer-items {
  flex: 1;
  overflow-y: auto;
}

.drawer-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--sky-border-soft);
}

.drawer-item > img,
.drawer-item-placeholder {
  flex: 0 0 60px;
  width: 60px;
  height: 60px;
  border-radius: 8px;
  background: var(--sky-surface-2);
  object-fit: cover;
}

.drawer-item-placeholder {
  display: grid;
  place-items: center;
  color: var(--sky-text-weak);
  font-size: 20px;
  font-weight: 600;
}

.drawer-item-info {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 3px;
}

.drawer-item-info strong,
.drawer-item-info small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-item-info strong {
  font-size: 14px;
}

.drawer-item-info small {
  color: var(--sky-text-weak);
  font-size: 12px;
}

.drawer-item-info span {
  color: var(--sky-primary);
  font-size: 14px;
  font-weight: 650;
}

.drawer-stepper {
  display: flex;
  align-items: center;
  gap: 9px;
}

.drawer-stepper button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid var(--sky-border);
  border-radius: 50%;
  color: var(--sky-text);
  font-size: 18px;
}

.drawer-stepper button:last-child {
  border-color: var(--sky-primary);
  background: var(--sky-primary);
  color: #fff;
}

.drawer-stepper button:disabled {
  opacity: 0.55;
}

.drawer-stepper span {
  min-width: 18px;
  font-size: 14px;
  text-align: center;
}

.drawer-summary {
  padding-top: 16px;
  border-top: 1px solid var(--sky-border);
}

.drawer-summary > div:not(.drawer-actions) {
  display: flex;
  justify-content: space-between;
  padding: 5px 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.drawer-summary .drawer-grand-total {
  margin-top: 8px;
  border-top: 1px solid var(--sky-border-soft);
  color: var(--sky-text) !important;
  font-size: 16px !important;
}

.drawer-grand-total strong:last-child,
.dialog-footer > strong {
  color: var(--sky-primary);
}

.drawer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
}

.clear-cart-button {
  color: var(--sky-text-sub);
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.flavor-group + .flavor-group {
  margin-top: 20px;
}

.flavor-group > p,
.setmeal-contents h3 {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.flavor-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.flavor-options button {
  min-height: 36px;
  padding: 0 13px;
  border: 1px solid var(--sky-border);
  border-radius: var(--sky-radius);
  color: var(--sky-text-sub);
  font-size: 13px;
}

.flavor-options button:hover,
.flavor-options button.selected {
  border-color: var(--sky-primary);
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 18px;
  margin-top: 20px;
  border-top: 1px solid var(--sky-border-soft);
}

.dialog-footer > strong {
  font-size: 20px;
}

.dish-detail > img {
  width: 100%;
  max-height: 300px;
  border-radius: var(--sky-radius);
  object-fit: cover;
}

.dish-detail-page {
  position: fixed;
  z-index: 1200;
  inset: 0;
  overflow: hidden;
  background: var(--sky-surface);
  color: var(--sky-text);
}

.dish-detail-shell {
  display: grid;
  width: 100%;
  height: 100%;
  grid-template-rows: auto minmax(0, 1fr) auto;
  background: var(--sky-surface);
}

.dish-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  padding: 0 max(24px, calc((100vw - 1120px) / 2));
  border-bottom: 1px solid var(--sky-border-soft);
  background: var(--sky-surface);
}

.detail-back-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 12px;
  border-radius: var(--sky-radius);
  color: var(--sky-text-sub);
  font-size: 14px;
}

.detail-back-button:hover {
  background: var(--sky-surface-2);
  color: var(--sky-primary);
}

.detail-category,
.detail-eyebrow {
  color: var(--sky-text-weak);
  font-size: 13px;
}

.dish-detail-scroll {
  overflow-y: auto;
  overscroll-behavior: contain;
}

.dish-detail-content {
  display: grid;
  width: min(1120px, 100%);
  min-height: 100%;
  grid-template-columns: minmax(0, 1.05fr) minmax(340px, 0.95fr);
  align-items: start;
  gap: clamp(28px, 5vw, 72px);
  padding: 42px 24px 48px;
  margin-inline: auto;
}

.dish-detail-hero {
  position: sticky;
  top: 24px;
  width: 100%;
  max-height: min(68dvh, 620px);
  aspect-ratio: 1.08;
  border-radius: 18px;
  background: var(--sky-surface-2);
  object-fit: cover;
}

.dish-detail-placeholder {
  display: grid;
  place-items: center;
  color: var(--sky-text-weak);
  font-size: 48px;
  font-weight: 700;
}

.dish-detail-copy {
  min-width: 0;
  padding-top: 10px;
}

.detail-eyebrow {
  margin: 0 0 9px;
  color: var(--sky-primary);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.08em;
}

.dish-detail-copy h1 {
  margin: 0;
  color: var(--sky-text);
  font-size: clamp(26px, 3vw, 38px);
  font-weight: 700;
  line-height: 1.3;
}

.dish-detail-description {
  margin: 12px 0 0;
  color: var(--sky-text-sub);
  font-size: 15px;
  line-height: 1.8;
}

.dish-detail-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 82px;
  padding: 12px max(24px, calc((100vw - 1120px) / 2));
  border-top: 1px solid var(--sky-border-soft);
  background: var(--sky-surface);
}

.dish-detail-footer > strong {
  color: var(--sky-primary);
  font-size: 24px;
}

.dish-detail-footer .checkout-button {
  min-width: 180px;
}

:global(::view-transition-group(dish-detail-shared)) {
  border-radius: 14px;
  box-shadow: none;
  filter: none;
  animation-duration: 320ms;
  animation-timing-function: cubic-bezier(0.2, 0.7, 0.2, 1);
}

:global(::view-transition-old(dish-detail-shared)),
:global(::view-transition-new(dish-detail-shared)) {
  height: 100%;
  box-shadow: none !important;
  filter: none !important;
  animation-duration: 320ms;
  animation-timing-function: cubic-bezier(0.2, 0.7, 0.2, 1);
}

:global(::view-transition-old(dish-detail-shared)) {
  animation-name: dish-shared-old;
}

:global(::view-transition-new(dish-detail-shared)) {
  animation-name: dish-shared-new;
}

@keyframes dish-shared-old {
  to {
    opacity: 0.88;
    filter: blur(0);
    transform: translateY(1px);
  }
}

@keyframes dish-shared-new {
  from {
    opacity: 0.88;
    filter: blur(0);
    transform: translateY(1px);
  }
}

.dish-detail > p {
  margin-top: 14px;
  color: var(--sky-text-sub);
  font-size: 14px;
}

.setmeal-contents {
  margin-top: 18px;
}

.setmeal-contents > div {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 0;
  border-bottom: 1px solid var(--sky-border-soft);
  font-size: 13px;
}

.setmeal-contents small {
  color: var(--sky-text-weak);
  text-align: right;
}

.dish-review-preview {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--sky-border-soft);
}

.review-preview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.review-preview-heading h3 {
  margin: 0;
  font-size: 14px;
}

.review-preview-heading > span {
  color: var(--sky-primary);
  font-size: 12px;
  text-decoration: none;
}

.review-preview-card {
  padding: 10px 0;
  border-bottom: 1px solid var(--sky-border-soft);
}

.review-preview-card > div:first-child {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--sky-text-sub);
  font-size: 12px;
}

.review-preview-card p {
  margin: 8px 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.review-preview-images {
  display: flex;
  gap: 6px;
}

.review-preview-images img {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  object-fit: cover;
}

@media (max-width: 1150px) {
  .store-toolbar {
    grid-template-columns: minmax(170px, 1fr) auto minmax(180px, 1.1fr) auto;
    gap: 9px;
  }

  .delivery-mode {
    display: none;
  }

  .campaign-banner {
    padding-inline: 36px;
  }

  .campaign-art {
    right: 5%;
    transform: translateY(-50%) scale(0.88);
  }

  .recommendation-card {
    flex-basis: calc((100% - 36px) / 2.5);
  }

  .dish-grid {
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
  }
}

@media (max-width: 900px) {
  .campaign-banner {
    min-height: 242px;
    padding-inline: 30px;
  }

  .campaign-art {
    right: -1%;
    transform: translateY(-50%) scale(0.78);
  }

  .campaign-copy {
    max-width: 62%;
  }

  .recommendation-card {
    flex-basis: calc((100% - 24px) / 2.2);
  }

  .store-toolbar {
    grid-template-columns: minmax(160px, 1fr) auto auto;
  }

  .delivery-address {
    grid-column: 1;
    grid-row: 1;
  }

  .delivery-mode {
    display: inline-flex;
    grid-column: 2;
    grid-row: 1;
    min-height: 42px;
  }

  .menu-search {
    grid-column: 1 / 3;
    grid-row: 2;
  }

  .menu-search :deep(.el-input__wrapper) {
    min-height: 42px;
  }

  .filter-wrap {
    grid-column: 3;
    grid-row: 2;
  }

  .toolbar-cart {
    grid-column: 3;
    grid-row: 1;
  }

  .dish-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
  }

  .cart-dock {
    left: calc(50% + 38px);
    width: min(760px, calc(100vw - 112px));
  }
}

@media (max-width: 600px) {
  .dish-detail-header {
    min-height: 58px;
    padding-inline: 14px;
  }

  .dish-detail-content {
    display: block;
    padding: 16px 14px 28px;
  }

  .dish-detail-hero {
    position: static;
    max-height: 42dvh;
    aspect-ratio: 1.2;
    border-radius: 14px;
  }

  .dish-detail-copy {
    padding-top: 22px;
  }

  .dish-detail-copy h1 {
    font-size: 26px;
  }

  .dish-detail-footer {
    min-height: 72px;
    padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  }

  .dish-detail-footer .checkout-button {
    min-width: 150px;
  }

  .storefront {
    padding-bottom: calc(144px + env(safe-area-inset-bottom));
  }

  .campaign-banner {
    min-height: 256px;
    padding: 30px 22px 60px;
    border-radius: 17px;
  }

  .campaign-copy {
    max-width: 100%;
  }

  .campaign-eyebrow {
    margin-bottom: 9px;
    font-size: 9px;
  }

  .campaign-copy h1 {
    max-width: 15ch;
    font-size: clamp(26px, 8vw, 34px);
  }

  .campaign-description {
    max-width: 30ch;
    font-size: 12px;
  }

  .campaign-action {
    min-height: 38px;
    margin-top: 16px;
    font-size: 12px;
  }

  .campaign-art {
    top: auto;
    right: -111px;
    bottom: -82px;
    opacity: 0.42;
    transform: scale(0.76);
    transform-origin: center;
  }

  .campaign-indicators {
    bottom: 22px;
    left: 22px;
  }

  .campaign-controls {
    right: 14px;
    bottom: 13px;
  }

  .campaign-controls button {
    width: 32px;
    height: 32px;
  }

  .store-toolbar {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px 10px;
    min-height: 0;
    padding: 10px;
    border-radius: 13px;
  }

  .delivery-address {
    grid-column: 1;
    grid-row: 1;
    min-height: 40px;
  }

  .delivery-mode {
    grid-column: 2;
    grid-row: 1;
    min-height: 40px;
    padding-inline: 9px;
    font-size: 11px;
  }

  .delivery-copy strong {
    max-width: min(56vw, 260px);
  }

  .menu-search {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .menu-search :deep(.el-input__wrapper) {
    min-height: 40px;
  }

  .filter-wrap {
    grid-column: 1;
    grid-row: 3;
  }

  .filter-button {
    max-width: 190px;
    min-height: 40px;
  }

  .toolbar-cart {
    grid-column: 2;
    grid-row: 3;
    justify-self: end;
    min-height: 40px;
  }

  .recommendations-section {
    margin-top: 28px;
  }

  .recommendations-section .section-heading {
    align-items: center;
  }

  .recommendations-section .section-heading > div {
    min-width: 0;
  }

  .recommendation-heading-actions {
    gap: 5px;
  }

  .carousel-control {
    width: 30px;
    height: 30px;
  }

  .browse-menu-button {
    min-height: 32px;
    padding-inline: 8px;
    font-size: 10px;
  }

  .recommendation-results {
    min-height: 286px;
    margin-top: 12px;
  }

  .recommendation-track {
    gap: 12px;
  }

  .recommendation-card {
    flex-basis: calc((100% - 18px) / 1.25);
    min-width: 0;
  }

  .recommendation-image {
    min-height: 138px;
  }

  .recommendation-card-body {
    padding: 13px 13px 14px;
  }

  .recommendation-sales {
    font-size: 9px;
  }

  .menu-section {
    padding-top: 20px;
    scroll-margin-top: 72px;
  }

  .section-heading h1,
  .section-heading h2 {
    font-size: 22px;
  }

  .menu-subtitle {
    font-size: 11px;
  }

  .category-nav {
    margin-top: 12px;
  }

  .menu-results {
    margin-top: 12px;
  }

  .dish-grid {
    gap: 10px;
  }

  .cart-dock {
    right: 12px;
    bottom: calc(70px + env(safe-area-inset-bottom));
    left: 12px;
    width: auto;
    min-height: 62px;
    gap: 8px;
    padding: 7px 8px 7px 10px;
    border-radius: 12px;
    transform: none;
  }

  .cart-overview {
    gap: 8px;
  }

  .cart-icon {
    flex-basis: 38px;
    width: 38px;
    height: 38px;
    font-size: 19px;
  }

  .cart-copy strong {
    font-size: 12px;
  }

  .cart-copy small {
    font-size: 9px;
  }

  .cart-total {
    font-size: 14px;
  }

  .cart-dock > .checkout-button {
    min-width: 78px;
    min-height: 40px;
    padding-inline: 12px;
    font-size: 12px;
  }
}

@media (max-width: 380px) {
  .campaign-banner {
    min-height: 244px;
    padding-inline: 18px;
  }

  .campaign-copy h1 {
    font-size: 26px;
  }

  .recommendation-heading-actions .carousel-control {
    display: none;
  }

  .recommendation-card {
    flex-basis: 88%;
  }

  .delivery-copy strong {
    max-width: 55vw;
  }

  .delivery-address {
    gap: 6px;
  }

  .dish-grid {
    gap: 8px;
  }

  :deep(.dish-foot) {
    gap: 6px;
  }

  :deep(.dish-main) {
    padding: 9px 8px 11px;
  }

  :deep(.dish-price) {
    font-size: 16px;
  }

  :deep(.stepper-btn) {
    width: 27px;
    height: 27px;
  }

  :deep(.dish-stepper) {
    gap: 5px;
  }

  :deep(.stepper-count) {
    min-width: 14px;
    font-size: 13px;
  }

  .cart-copy small {
    display: none;
  }

  .cart-total {
    display: block;
    font-size: 13px;
  }
}
</style>
