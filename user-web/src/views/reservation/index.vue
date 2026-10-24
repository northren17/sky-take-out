<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, Clock, Plus, ShoppingBag } from '@element-plus/icons-vue'
import { getImageUrl } from '@/api/request'
import {
  cancelReservation,
  createReservation,
  getReservationCapacity,
  listCategories,
  listDishes,
  listReservations,
  type MealProduct,
  type Reservation,
  type ReservationLine,
} from '@/api/modules/meal'
import { setmealListByCategoryId } from '@/api/modules/dish'
import { SUCCESS_CODE } from '@/constants/business'

type ProductChoice = MealProduct & { itemType: 1 | 2 }
const tomorrow = new Date(Date.now() + 86400000)
const minDate = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`
const mealDate = ref(minDate)
const mealPeriod = ref<1 | 2>(1)
const remark = ref('')
const productType = ref<1 | 2>(1)
const activeCategory = ref<number | 'all'>('all')
const categories = ref<Array<{ id: number; name: string; type: number }>>([])
const products = ref<ProductChoice[]>([])
const selected = ref<Record<string, { item: ProductChoice; quantity: number }>>({})
const reservations = ref<Reservation[]>([])
const capacity = ref<{ maxReservations: number; enabled: number }>()
const loading = ref(false)
const submitting = ref(false)
const listLoading = ref(false)

const availableCategories = computed(() => categories.value.filter((item) => item.type === productType.value))
const selectedLines = computed(() => Object.values(selected.value))
const total = computed(() =>
  selectedLines.value.reduce((sum, line) => sum + Number(line.item.price) * line.quantity, 0),
)

async function loadProducts() {
  loading.value = true
  try {
    const selectedCategories =
      activeCategory.value === 'all'
        ? availableCategories.value
        : availableCategories.value.filter((category) => category.id === activeCategory.value)
    const responses = await Promise.all(
      selectedCategories.map((category) =>
        productType.value === 1
          ? listDishes(category.id)
          : setmealListByCategoryId({ categoryId: category.id }),
      ),
    )
    products.value = responses.flatMap((response) =>
      response.code === SUCCESS_CODE && Array.isArray(response.data)
        ? (response.data as MealProduct[]).map((item) => ({ ...item, itemType: productType.value }))
        : [],
    )
  } finally {
    loading.value = false
  }
}

function addProduct(item: ProductChoice) {
  const key = `${item.itemType}:${item.id}`
  const current = selected.value[key]
  selected.value[key] = { item, quantity: Math.min(99, (current?.quantity ?? 0) + 1) }
}

function changeQuantity(key: string, amount: number) {
  const line = selected.value[key]
  if (!line) return
  const quantity = line.quantity + amount
  if (quantity <= 0) delete selected.value[key]
  else line.quantity = Math.min(quantity, 99)
}

async function loadReservations() {
  listLoading.value = true
  try {
    const response = await listReservations()
    if (response.code === SUCCESS_CODE) reservations.value = response.data ?? []
  } finally {
    listLoading.value = false
  }
}

async function loadCapacity() {
  if (!mealDate.value) return
  const response = await getReservationCapacity(mealDate.value, mealPeriod.value)
  if (response.code === SUCCESS_CODE) capacity.value = response.data
}

async function submitReservation() {
  if (!mealDate.value || mealDate.value < minDate || selectedLines.value.length === 0) {
    ElMessage.warning('请选择未来就餐日期并添加预约商品')
    return
  }
  submitting.value = true
  try {
    const items: ReservationLine[] = selectedLines.value.map(({ item, quantity }) => ({
      itemType: item.itemType,
      itemId: item.id,
      quantity,
    }))
    const response = await createReservation({
      requestId: crypto.randomUUID(),
      mealDate: mealDate.value,
      mealPeriod: mealPeriod.value,
      remark: remark.value.trim(),
      items,
    })
    if (response.code === SUCCESS_CODE) {
      ElMessage.success('预约提交成功')
      selected.value = {}
      remark.value = ''
      await Promise.all([loadReservations(), loadCapacity()])
    }
  } finally {
    submitting.value = false
  }
}

async function cancel(id: number) {
  try {
    await ElMessageBox.confirm('确定取消这笔预约吗？', '取消预约', {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      type: 'warning',
    })
    const response = await cancelReservation(id)
    if (response.code === SUCCESS_CODE) {
      ElMessage.success('预约已取消')
      await Promise.all([loadReservations(), loadCapacity()])
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

function statusText(status: number) {
  return ['待取餐', '已取餐', '已取消', '超时未取'][status] ?? '状态未知'
}

function setProductType(type: 1 | 2) {
  productType.value = type
  activeCategory.value = 'all'
  void loadProducts()
}

watch([mealDate, mealPeriod], () => void loadCapacity())
onMounted(() => {
  void (async () => {
    const categoryResponse = await listCategories()
    if (categoryResponse.code === SUCCESS_CODE) {
      categories.value = (categoryResponse.data ?? []).map((category) => ({
        ...category,
        type: Number(category.type),
      }))
    }
    await Promise.all([loadProducts(), loadReservations(), loadCapacity()])
  })()
})
</script>

<template>
  <section class="reservation-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">PLAN YOUR MEAL</p>
        <h1>预订点餐</h1>
        <p>提前选好餐品，到食堂轻松取餐</p>
      </div>
      <el-tag v-if="capacity" :type="capacity.enabled ? 'success' : 'info'">
        {{ capacity.enabled ? `本时段限额 ${capacity.maxReservations} 单` : '本时段暂停预约' }}
      </el-tag>
    </header>

    <!-- Left menu scrolls independently; the booking card stays visible beside it. -->
    <div class="reservation-layout">
      <section class="product-pane" aria-label="选择预约商品">
        <div class="product-type-tabs">
          <button :class="{ active: productType === 1 }" type="button" @click="setProductType(1)">
            单点菜品
          </button>
          <button :class="{ active: productType === 2 }" type="button" @click="setProductType(2)">
            套餐
          </button>
        </div>
        <nav class="category-tabs" aria-label="商品分类">
          <button
            type="button"
            :class="{ active: activeCategory === 'all' }"
            @click="activeCategory = 'all'; void loadProducts()"
          >
            全部
          </button>
          <button
            v-for="category in availableCategories"
            :key="category.id"
            type="button"
            :class="{ active: activeCategory === category.id }"
            @click="activeCategory = category.id; void loadProducts()"
          >
            {{ category.name }}
          </button>
        </nav>

        <div v-loading="loading" class="product-grid">
          <article
            v-for="(item, index) in products"
            :key="`${item.itemType}:${item.id}`"
            class="product-card product-enter"
            :style="{ '--product-enter-delay': `${Math.min(index, 7) * 45}ms` }"
          >
            <img v-image-fallback :src="getImageUrl(item.image)" :alt="item.name" />
            <div class="product-info">
              <strong>{{ item.name }}</strong>
              <span>￥{{ Number(item.price).toFixed(2) }}</span>
              <el-button circle type="primary" :icon="Plus" aria-label="加入预约清单" @click="addProduct(item)" />
            </div>
          </article>
          <el-empty v-if="!loading && products.length === 0" description="该分类暂无可预约商品" />
        </div>
      </section>

      <aside class="booking-pane" aria-label="预约信息与清单">
        <section class="booking-card">
          <div class="booking-card-heading">
            <div>
              <p class="eyebrow">RESERVATION DETAILS</p>
              <h2>预约信息</h2>
            </div>
            <el-tag v-if="capacity" size="small" :type="capacity.enabled ? 'success' : 'info'">
              {{ capacity.enabled ? `限额 ${capacity.maxReservations}` : '暂停预约' }}
            </el-tag>
          </div>
          <label class="date-control">
            <span>就餐日期</span>
            <span class="date-input">
              <el-icon><Calendar /></el-icon>
              <input v-model="mealDate" type="date" :min="minDate" />
            </span>
          </label>
          <div class="period-control" role="group" aria-label="就餐时段">
            <button :class="{ active: mealPeriod === 1 }" type="button" @click="mealPeriod = 1">
              <el-icon><Clock /></el-icon> 午餐
            </button>
            <button :class="{ active: mealPeriod === 2 }" type="button" @click="mealPeriod = 2">
              <el-icon><Clock /></el-icon> 晚餐
            </button>
          </div>
          <label class="remark-control">
            <span>备注</span>
            <el-input
              v-model="remark"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="口味要求或取餐备注（选填）"
            />
          </label>
        </section>

        <section class="booking-card order-card">
          <div class="selected-heading">
            <h2><el-icon><ShoppingBag /></el-icon> 预约清单</h2>
            <span>{{ selectedLines.length }} 种商品</span>
          </div>
          <div v-if="selectedLines.length" class="selected-list">
            <div v-for="({ item, quantity }, key) in selected" :key="key" class="selected-line">
              <span class="selected-name">{{ item.name }}</span>
              <div class="quantity-control">
                <button type="button" :aria-label="`减少${item.name}`" @click="changeQuantity(String(key), -1)">
                  −
                </button>
                <span>{{ quantity }}</span>
                <button type="button" :aria-label="`增加${item.name}`" @click="changeQuantity(String(key), 1)">
                  +
                </button>
              </div>
              <strong>￥{{ (Number(item.price) * quantity).toFixed(2) }}</strong>
            </div>
          </div>
          <el-empty v-else description="还没有添加商品" :image-size="70" />
          <div class="submit-row">
            <span>预计金额 <strong>￥{{ total.toFixed(2) }}</strong></span>
            <el-button
              type="primary"
              round
              :loading="submitting"
              :disabled="capacity?.enabled === 0"
              @click="submitReservation"
            >
              提交预约
            </el-button>
          </div>
        </section>
      </aside>
    </div>

    <section class="reservation-history booking-card" aria-label="我的预约">
      <div class="section-heading">
        <h2>我的预约</h2>
        <el-button text @click="loadReservations">刷新</el-button>
      </div>
      <div v-loading="listLoading" class="reservation-list">
        <article
          v-for="reservation in reservations"
          :key="reservation.id"
          class="reservation-history-card"
        >
          <div class="reservation-head">
            <strong>{{ reservation.mealDate }} · {{ reservation.mealPeriod === 1 ? '午餐' : '晚餐' }}</strong>
            <el-tag size="small" :type="reservation.status === 0 ? 'success' : 'info'">
              {{ statusText(reservation.status) }}
            </el-tag>
          </div>
          <p>预约单号 {{ reservation.reservationNo }}</p>
          <div class="reservation-foot">
            <span>{{ reservation.items?.map((item) => `${item.itemName} × ${item.quantity}`).join('、') }}</span>
            <strong>￥{{ Number(reservation.totalAmount).toFixed(2) }}</strong>
            <el-button
              v-if="reservation.status === 0"
              type="danger"
              plain
              size="small"
              @click="cancel(reservation.id)"
            >
              取消预约
            </el-button>
          </div>
        </article>
        <el-empty v-if="!listLoading && reservations.length === 0" description="暂无预约订单" :image-size="70" />
      </div>
    </section>
  </section>
</template>

<style scoped>
.reservation-page {
  display: flex;
  min-width: 0;
  flex-direction: column;
  color: #24372e;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 0 16px;
}

.page-heading h1 {
  margin: 4px 0 6px;
  color: #153d31;
  font-size: 28px;
}

.page-heading p:last-child {
  margin: 0;
  color: #7a8983;
}

.eyebrow {
  margin: 0;
  color: #4a9a79;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .14em;
}

.reservation-layout {
  display: grid;
  min-height: 500px;
  height: calc(100dvh - 286px);
  max-height: 820px;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 0.78fr);
  gap: 18px;
}

.product-pane,
.booking-card {
  min-width: 0;
  border: 1px solid #edf1ee;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 9px 28px rgb(35 80 59 / 6%);
}

.product-pane {
  overflow: auto;
  padding: 18px;
  overscroll-behavior: contain;
  scrollbar-color: #c8d9ce transparent;
  scrollbar-width: thin;
}

.product-type-tabs,
.category-tabs,
.period-control {
  display: flex;
  gap: 8px;
}

.product-type-tabs {
  padding: 4px 0 12px;
}

.product-type-tabs button,
.category-tabs button,
.period-control button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 15px;
  border: 1px solid #e4ece7;
  border-radius: 10px;
  background: #fff;
  color: #617169;
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease, color 160ms ease,
    transform 300ms var(--sky-press-release);
}

.product-type-tabs button.active,
.category-tabs button.active,
.period-control button.active {
  border-color: #16785b;
  background: #eaf5ef;
  color: #16785b;
  font-weight: 650;
}

.category-tabs {
  overflow-x: auto;
  padding: 0 0 14px;
  scrollbar-width: thin;
}

.product-grid {
  display: grid;
  min-height: 120px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  gap: 12px;
}

.product-enter {
  animation: reservation-product-enter 480ms cubic-bezier(0.2, 1.15, 0.35, 1) backwards;
  animation-delay: var(--product-enter-delay, 0ms);
}

@keyframes reservation-product-enter {
  from {
    opacity: 0.8;
    transform: translateY(9px) scale(0.99);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.product-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border: 1px solid #edf1ee;
  border-radius: 12px;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.product-card:hover {
  border-color: #cfe3d6;
  box-shadow: 0 5px 16px rgb(35 80 59 / 8%);
}

.product-card img {
  flex: 0 0 68px;
  width: 68px;
  height: 68px;
  border-radius: 9px;
  background: #f1f5f2;
  object-fit: cover;
}

.product-info {
  display: grid;
  flex: 1;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.product-info strong {
  overflow: hidden;
  grid-column: 1 / -1;
  color: #34483d;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-info > span {
  color: #cf7047;
  font-weight: 700;
}

.booking-pane {
  display: grid;
  min-height: 0;
  grid-template-rows: minmax(250px, 0.9fr) minmax(250px, 1.1fr);
  gap: 14px;
}

.booking-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid #edf1ee;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 9px 28px rgb(35 80 59 / 6%);
}

.booking-card-heading,
.selected-heading,
.section-heading,
.reservation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.booking-card-heading {
  margin-bottom: 14px;
}

.booking-card-heading h2,
.selected-heading h2,
.section-heading h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 0;
  color: #244135;
  font-size: 16px;
}

.date-control,
.remark-control {
  display: grid;
  gap: 7px;
  color: #5b6d62;
  font-size: 12px;
}

.date-input {
  display: flex;
  min-height: 40px;
  align-items: center;
  gap: 9px;
  padding: 0 10px;
  border: 1px solid #e2eae4;
  border-radius: 9px;
  color: #16785b;
}

.date-input input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  color: #26382f;
  font: inherit;
}

.period-control {
  margin: 12px 0;
}

.period-control button {
  flex: 1;
  min-height: 36px;
}

.remark-control :deep(.el-textarea__inner) {
  min-height: 54px;
  padding: 8px 10px;
  border-color: #e2eae4;
  box-shadow: none;
  font-size: 12px;
}

.remark-control :deep(.el-textarea__inner:focus) {
  border-color: #16785b;
}

.remark-control :deep(.el-input__count) {
  font-size: 10px;
}

.order-card {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

.selected-heading h2 {
  margin: 0;
}

.selected-heading > span {
  color: #8a9890;
  font-size: 12px;
}

.selected-list {
  flex: 1 1 0;
  min-height: 0;
  margin-top: 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: #c8d9ce transparent;
  scrollbar-width: thin;
}

.selected-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f0f3f1;
}

.selected-name {
  overflow: hidden;
  color: #52635a;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-line > strong,
.submit-row strong {
  color: #cb6d45;
  font-size: 12px;
}

.quantity-control {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #52635a;
  font-size: 12px;
}

.quantity-control button {
  width: 24px;
  height: 24px;
  border: 1px solid #dce8df;
  border-radius: 50%;
  background: #f7faf8;
  color: #16785b;
  cursor: pointer;
}

.order-card :deep(.el-empty) {
  flex: 1 1 0;
  padding: 8px 0;
}

.order-card :deep(.el-empty__description) {
  margin-top: 4px;
}

.submit-row {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #edf1ee;
  color: #697971;
  font-size: 12px;
}

.submit-row strong {
  margin-left: 4px;
  font-size: 18px;
}

.reservation-history {
  display: flex;
  flex-direction: column;
  min-height: 240px;
  margin-top: 18px;
  padding-top: 12px;
}

.section-heading h2 {
  margin: 0;
}

.reservation-list {
  min-height: 120px;
  max-height: 360px;
  margin-top: 5px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: #c8d9ce transparent;
  scrollbar-width: thin;
}

.reservation-history-card {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #edf1ee;
  border-radius: 10px;
}

.reservation-head strong {
  color: #40564a;
  font-size: 12px;
}

.reservation-history-card p {
  margin: 7px 0;
  color: #86948c;
  font-size: 10px;
}

.reservation-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  color: #65746c;
  font-size: 11px;
}

.reservation-foot span {
  overflow: hidden;
  flex: 1 1 100%;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reservation-foot > strong {
  margin-right: auto;
  color: #cb6d45;
}

@media (max-width: 1100px) {
  .reservation-page {
    padding-bottom: 30px;
  }

  .reservation-layout {
    height: auto;
    max-height: none;
    grid-template-columns: minmax(0, 1fr);
  }

  .product-pane {
    max-height: 55vh;
  }

  .booking-pane {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    grid-template-rows: minmax(250px, auto);
    align-items: stretch;
  }

  .order-card {
    min-height: 400px;
  }

  .reservation-history {
    min-height: 220px;
  }
}

@media (max-width: 700px) {
  .page-heading {
    padding-top: 14px;
  }

  .page-heading h1 {
    font-size: 24px;
  }

  .reservation-layout {
    gap: 12px;
  }

  .product-pane,
  .booking-card {
    padding: 14px;
  }

  .booking-pane {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto auto;
  }

  .product-pane {
    max-height: 50vh;
  }

  .product-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .order-card {
    min-height: 420px;
  }

  .reservation-history {
    min-height: 240px;
  }

  .reservation-list {
    max-height: 420px;
  }
}

@media (min-width: 1101px) and (max-height: 720px) {
  .reservation-layout {
    min-height: 460px;
    height: calc(100dvh - 286px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .product-enter {
    animation: none;
  }
}
</style>
