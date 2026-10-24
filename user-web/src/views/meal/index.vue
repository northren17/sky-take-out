<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getImageUrl } from '@/api/request'
import { listCategories, type MealProduct } from '@/api/modules/meal'
import { setmealDishList, setmealListByCategoryId } from '@/api/modules/dish'
import { SUCCESS_CODE } from '@/constants/business'
import { fenToYuan, useCartStore } from '@/stores/cart'

const router = useRouter()
const cart = useCartStore()
const loading = ref(false)
const products = ref<MealProduct[]>([])
const selected = ref<number | 'all'>('all')
const categories = ref<Array<{ id: number; name: string }>>([])

const tabs = computed(() => [
  { id: 'all' as const, name: '全部套餐' },
  ...categories.value.map(({ id, name }) => ({ id, name })),
])
const cartCount = computed(() => cart.totalCount)

async function loadProducts() {
  loading.value = true
  try {
    const response = await listCategories()
    if (response.code !== SUCCESS_CODE) return
    const mealCategories = (response.data ?? []).filter((category) => Number(category.type) === 2)
    categories.value = mealCategories.map(({ id, name }) => ({ id, name }))
    const selectedCategories = selected.value === 'all'
      ? mealCategories
      : mealCategories.filter((category) => category.id === selected.value)
    const results = await Promise.all(
      selectedCategories.map((category) => setmealListByCategoryId({ categoryId: category.id })),
    )
    const rows = results.flatMap((result) => {
      if (result.code !== SUCCESS_CODE || !Array.isArray(result.data)) return []
      return result.data as MealProduct[]
    })
    products.value = await Promise.all(
      rows.map(async (item) => {
        const response = await setmealDishList(item.id)
        return {
          ...item,
          contents: response.code === SUCCESS_CODE ? response.data ?? [] : [],
        }
      }),
    )
  } finally {
    loading.value = false
  }
}

async function addToCart(item: MealProduct) {
  if (Number(item.status ?? 1) !== 1 || (item.stock !== undefined && item.stock === 0)) {
    ElMessage.warning('该套餐暂不可购买')
    return
  }
  const response = await cart.addItem({ setmealId: item.id })
  if (response?.code === SUCCESS_CODE) ElMessage.success('已加入购物车')
}

function openOrder() {
  if (cartCount.value > 0) void router.push('/order')
  else ElMessage.info('购物车还是空的，先选一份套餐吧')
}

onMounted(() => {
  void loadProducts()
  void cart.fetchCart()
})
</script>

<template>
  <section class="meal-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">MEAL COLLECTION</p>
        <h1>套餐专区</h1>
        <p>营养搭配，一份解决每餐选择</p>
      </div>
      <el-button type="primary" plain @click="router.push('/reservation')">预约点餐</el-button>
    </header>

    <nav class="category-tabs" aria-label="套餐分类">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="{ active: selected === tab.id }"
        @click="selected = tab.id; void loadProducts()"
      >
        {{ tab.name }}
      </button>
    </nav>

    <div v-loading="loading" class="meal-grid">
      <article v-for="item in products" :key="item.id" class="meal-card">
        <div class="meal-cover">
          <img v-image-fallback :src="getImageUrl(item.image)" :alt="item.name" />
          <span v-if="item.stock === 0" class="sold-out">暂时售罄</span>
          <span v-else class="meal-tag">精选套餐</span>
        </div>
        <div class="meal-info">
          <div class="meal-title-row">
            <h2>{{ item.name }}</h2>
            <span class="meal-price">￥{{ fenToYuan(Number(item.price)).toFixed(2) }}</span>
          </div>
          <p class="meal-description">{{ item.description || '精选菜品搭配，满足一餐营养所需' }}</p>
          <div v-if="item.contents?.length" class="meal-contents">
            <span v-for="dish in item.contents.slice(0, 3)" :key="dish.dishId ?? dish.name">
              {{ dish.name }} × {{ dish.copies }}
            </span>
          </div>
          <div class="meal-actions">
            <span class="meal-stock">{{ item.stock === -1 ? '库存充足' : `剩余 ${item.stock ?? '—'} 份` }}</span>
            <el-button
              type="primary"
              round
              :disabled="item.stock === 0 || Number(item.status ?? 1) !== 1"
              @click="addToCart(item)"
            >
              加入购物车
            </el-button>
          </div>
        </div>
      </article>
      <el-empty v-if="!loading && products.length === 0" description="暂无可售套餐" />
    </div>

    <footer class="cart-dock">
      <div class="cart-summary">
        <span class="cart-icon">{{ cartCount }}</span>
        <span>购物车</span>
        <strong>￥{{ cart.totalYuan.toFixed(2) }}</strong>
      </div>
      <el-button type="primary" round :disabled="cartCount === 0" @click="openOrder">去结算</el-button>
    </footer>
  </section>
</template>

<style scoped>
.meal-page { padding-bottom: 86px; color: #23332d; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 24px 0 18px; }
.page-heading h1 { margin: 4px 0 6px; color: #153d31; font-size: 28px; }
.page-heading p:last-child { margin: 0; color: #7a8983; }
.eyebrow { margin: 0; color: #4a9a79; font-size: 11px; font-weight: 700; letter-spacing: .14em; }
.category-tabs { display: flex; gap: 9px; padding: 5px 0 20px; overflow-x: auto; }
.category-tabs button { flex: 0 0 auto; padding: 9px 18px; border: 1px solid #e4ece7; border-radius: 22px; background: #fff; color: #62726a; cursor: pointer; transition: transform 300ms var(--sky-press-release), background-color 160ms ease, border-color 160ms ease, color 160ms ease; }
.category-tabs button.active { border-color: #16785b; background: #16785b; color: #fff; }
.meal-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; min-height: 260px; }
.meal-card { overflow: hidden; border: 1px solid #edf1ee; border-radius: 18px; background: #fff; box-shadow: 0 9px 28px rgb(35 80 59 / 6%); transition: transform .18s ease, box-shadow .18s ease; }
.meal-card:hover { transform: translateY(-3px); box-shadow: 0 14px 34px rgb(35 80 59 / 12%); }
.meal-cover { position: relative; height: 220px; background: #edf4ef; }
.meal-cover img { width: 100%; height: 100%; object-fit: cover; }
.meal-tag, .sold-out { position: absolute; top: 14px; left: 14px; padding: 6px 11px; border-radius: 14px; background: rgb(16 91 68 / 88%); color: #fff; font-size: 12px; }
.sold-out { background: rgb(82 91 86 / 82%); }
.meal-info { padding: 17px 19px 18px; }
.meal-title-row, .meal-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.meal-title-row h2 { overflow: hidden; margin: 0; color: #24372e; font-size: 18px; text-overflow: ellipsis; white-space: nowrap; }
.meal-price { flex: 0 0 auto; color: #d66a3d; font-size: 19px; font-weight: 750; }
.meal-description { min-height: 40px; margin: 10px 0 15px; color: #829088; font-size: 13px; line-height: 1.6; }
.meal-contents { display: flex; flex-wrap: wrap; gap: 6px; min-height: 24px; margin: -3px 0 12px; }
.meal-contents span { padding: 4px 8px; border-radius: 12px; background: #f2f7f3; color: #698074; font-size: 11px; }
.meal-stock { color: #8a9890; font-size: 12px; }
.cart-dock { position: fixed; right: max(32px, calc((100vw - 1560px) / 2 + 24px)); bottom: 20px; left: max(280px, calc((100vw - 1560px) / 2 + 256px)); z-index: 20; display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 9px 18px; border: 1px solid #e5eee8; border-radius: 16px; background: rgb(255 255 255 / 96%); box-shadow: 0 10px 32px rgb(30 72 51 / 12%); backdrop-filter: blur(12px); }
.cart-summary { display: flex; align-items: center; gap: 12px; color: #5d6d64; }
.cart-summary strong { margin-left: 14px; color: #d66a3d; font-size: 18px; }
.cart-icon { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 50%; background: #e8f4ee; color: #16785b; font-weight: 700; }
@media (max-width: 900px) { .meal-grid { grid-template-columns: 1fr; } .cart-dock { right: 20px; left: 96px; } }
@media (max-width: 600px) { .page-heading { align-items: flex-start; } .meal-cover { height: 190px; } .cart-dock { right: 14px; bottom: 12px; left: 90px; } }
</style>
