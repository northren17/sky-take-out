<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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
import type { AddressBook, Dish, DishItem, Setmeal, SourceDishCategory } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'

type DishRow = (Dish | Setmeal) & {
  type: number
  categoryId: number
  categoryName: string
}

interface CategorySpotlight {
  category: SourceDishCategory
  item?: DishRow
  itemCount: number
}

const router = useRouter()
const cart = useCartStore()
const checkout = useCheckoutStore()
const shop = useShopStore()

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
const setmealDishes = ref<DishItem[]>([])
const cartVisible = ref(false)

const allDishRows = computed(() =>
  categories.value.flatMap((category) => productsByCategory.value[Number(category.id)] ?? []),
)

const categorySpotlights = computed<CategorySpotlight[]>(() =>
  categories.value.slice(0, 6).map((category) => {
    const categoryItems = productsByCategory.value[Number(category.id)] ?? []
    return { category, item: categoryItems[0], itemCount: categoryItems.length }
  }),
)

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

async function selectSpotlight(category: SourceDishCategory) {
  await selectCategory(categoryKey(category))
  document.querySelector('#menu')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
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
  detailDish.value = row
  detailVisible.value = true
  setmealDishes.value = []
  if (row.type !== 2) return
  try {
    const res = await setmealDishList(Number(row.id))
    if (res.code === SUCCESS_CODE) setmealDishes.value = (res.data ?? []) as DishItem[]
  } catch {
    setmealDishes.value = []
  }
}

async function addFromDetail() {
  const row = detailDish.value
  if (!row) return
  detailVisible.value = false
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
  await Promise.all([shop.fetchAll(), cart.fetchCart(), loadDeliveryAddress()])
  await loadCategories()
})
</script>

<template>
  <div class="storefront">
    <section class="store-toolbar" aria-label="配送、搜索和购物车">
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

    <section v-if="categorySpotlights.length" class="spotlight-section" aria-labelledby="spotlight-title">
      <div class="section-heading compact-heading">
        <div>
          <p class="section-eyebrow">EXPLORE THE MENU</p>
          <h1 id="spotlight-title">逛逛分类</h1>
        </div>
        <span class="section-count">{{ categories.length }} 个分类</span>
      </div>

      <div class="spotlight-row">
        <button
          v-for="spotlight in categorySpotlights"
          :key="spotlight.category.id ?? spotlight.category.name"
          class="spotlight-card"
          type="button"
          @click="selectSpotlight(spotlight.category)"
        >
          <span class="spotlight-image">
            <img
              v-if="spotlight.item && getImageUrl(spotlight.item.image)"
              v-image-fallback
              :src="getImageUrl(spotlight.item.image)"
              :alt="String(spotlight.item.name ?? '')"
              loading="lazy"
            />
            <span v-else class="spotlight-placeholder" aria-hidden="true">
              <el-icon><Dish /></el-icon>
            </span>
          </span>
          <span class="spotlight-copy">
            <strong>{{ spotlight.category.name }}</strong>
            <small>
              {{ spotlight.item?.name ?? '查看分类' }}
              <template v-if="spotlight.itemCount"> · {{ spotlight.itemCount }} 款</template>
            </small>
          </span>
          <el-icon class="spotlight-arrow" aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </div>
    </section>

    <section id="menu" class="menu-section" aria-labelledby="menu-title">
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
            v-for="row in visibleRows"
            :key="`${row.type}-${row.id}`"
            :name="String(row.name ?? '')"
            :description="String(row.description ?? '')"
            :image="getImageUrl(row.image)"
            :price="Number(row.price ?? 0)"
            :count="countOf(row)"
            :has-flavor="hasFlavor(row)"
            :category="row.categoryName"
            :disabled="shop.isClosed"
            :loading="cart.loading"
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

    <el-dialog v-model="detailVisible" :title="String(detailDish?.name ?? '')" width="560px">
      <div v-if="detailDish" class="dish-detail">
        <img
          v-if="getImageUrl(detailDish.image)"
          v-image-fallback
          :src="getImageUrl(detailDish.image)"
          :alt="String(detailDish.name ?? '')"
        />
        <p>{{ detailDish.description || '本店现做' }}</p>
        <div v-if="detailDish.type === 2" class="setmeal-contents">
          <h3>套餐包含</h3>
          <div v-for="(dish, index) in setmealDishes" :key="index">
            <span>{{ dish.name }} × {{ dish.copies }}</span>
            <small>{{ dish.description }}</small>
          </div>
        </div>
        <div class="dialog-footer">
          <strong>￥{{ priceText(detailDish) }}</strong>
          <button
            class="checkout-button"
            type="button"
            :disabled="shop.isClosed || cart.loading"
            @click="addFromDetail"
          >
            {{ hasFlavor(detailDish) ? '选择规格' : '加入购物车' }}
          </button>
        </div>
      </div>
    </el-dialog>
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
  transition: background 160ms ease, transform 160ms ease;
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

.spotlight-section {
  margin-top: 28px;
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

.spotlight-row {
  display: grid;
  grid-auto-columns: minmax(145px, 1fr);
  grid-auto-flow: column;
  gap: 12px;
  margin-top: 14px;
  overflow-x: auto;
  padding: 1px 1px 9px;
  scrollbar-width: thin;
  scrollbar-color: var(--sky-border) transparent;
}

.spotlight-card {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  min-height: 84px;
  padding: 8px;
  border: 1px solid var(--sky-border-soft);
  border-radius: 13px;
  background: #fff;
  box-shadow: 0 2px 9px rgb(37 50 42 / 3%);
  text-align: left;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.spotlight-card:hover {
  border-color: #f3c8ba;
  box-shadow: var(--sky-shadow-sm);
  transform: translateY(-2px);
}

.spotlight-image {
  display: block;
  width: 68px;
  height: 60px;
  overflow: hidden;
  border-radius: 9px;
  background: var(--sky-surface-2);
}

.spotlight-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.spotlight-placeholder {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: var(--sky-text-weak);
  font-size: 23px;
}

.spotlight-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.spotlight-copy strong,
.spotlight-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spotlight-copy strong {
  color: var(--sky-text);
  font-size: 13px;
  font-weight: 650;
}

.spotlight-copy small {
  color: var(--sky-text-weak);
  font-size: 10px;
}

.spotlight-arrow {
  color: var(--sky-text-weak);
  font-size: 13px;
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
  transition: background 160ms ease, transform 160ms ease;
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

@media (max-width: 1150px) {
  .store-toolbar {
    grid-template-columns: minmax(170px, 1fr) auto minmax(180px, 1.1fr) auto;
    gap: 9px;
  }

  .delivery-mode {
    display: none;
  }

  .spotlight-row {
    grid-auto-columns: minmax(175px, 1fr);
  }

  .dish-grid {
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
  }
}

@media (max-width: 900px) {
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
  .storefront {
    padding-bottom: calc(144px + env(safe-area-inset-bottom));
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

  .spotlight-section {
    margin-top: 22px;
  }

  .spotlight-row {
    grid-auto-columns: minmax(205px, 72vw);
    gap: 10px;
    margin-top: 12px;
  }

  .spotlight-card {
    min-height: 78px;
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
