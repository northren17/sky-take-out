<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatLineRound, UploadFilled } from '@element-plus/icons-vue'
import { getImageUrl } from '@/api/request'
import {
  listMyReviews,
  listPendingReviewOrders,
  listSetmealDishes,
  submitOrderReview,
  uploadReviewImage,
  type ReviewEntry,
  type ReviewOrder,
} from '@/api/modules/meal'
import { SUCCESS_CODE } from '@/constants/business'

type DishRating = { orderDetailId: number; dishId: number; dishName: string; rating: number }
const activeTab = ref<'pending' | 'mine'>('pending')
const pendingOrders = ref<ReviewOrder[]>([])
const reviews = ref<ReviewEntry[]>([])
const selectedOrder = ref<ReviewOrder>()
const dialogVisible = ref(false)
const loading = ref(false)
const submitting = ref(false)
const uploadLoading = ref(false)
const overallRating = ref(5)
const content = ref('')
const images = ref<string[]>([])
const dishRatings = ref<DishRating[]>([])
const fileInput = ref<HTMLInputElement>()
const dialogTitle = computed(() => `评价订单 ${selectedOrder.value?.number ?? ''}`)

async function loadPending() {
  loading.value = true
  try {
    const response = await listPendingReviewOrders()
    if (response.code === SUCCESS_CODE) pendingOrders.value = response.data ?? []
  } finally {
    loading.value = false
  }
}

async function loadMine() {
  loading.value = true
  try {
    const response = await listMyReviews()
    if (response.code === SUCCESS_CODE) reviews.value = response.data ?? []
  } finally {
    loading.value = false
  }
}

async function changeTab(tab: 'pending' | 'mine') {
  activeTab.value = tab
  await (tab === 'pending' ? loadPending() : loadMine())
}

async function startReview(order: ReviewOrder) {
  selectedOrder.value = order
  overallRating.value = 5
  content.value = ''
  images.value = []
  dishRatings.value = []
  const details = order.orderDetails ?? []
  for (const detail of details) {
    if (detail.dishId) {
      dishRatings.value.push({
        orderDetailId: detail.id,
        dishId: detail.dishId,
        dishName: detail.name,
        rating: 5,
      })
    } else if (detail.setmealId) {
      const response = await listSetmealDishes(detail.setmealId)
      if (response.code === SUCCESS_CODE) {
        for (const dish of response.data ?? []) {
          if (dish.dishId) {
            dishRatings.value.push({
              orderDetailId: detail.id,
              dishId: dish.dishId,
              dishName: dish.name ?? '套餐菜品',
              rating: 5,
            })
          }
        }
      }
    }
  }
  dialogVisible.value = true
}

function chooseImages() {
  fileInput.value?.click()
}

async function onFilesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (images.value.length + files.length > 9) {
    ElMessage.warning('最多上传9张图片')
    input.value = ''
    return
  }
  uploadLoading.value = true
  try {
    for (const file of files) {
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
        ElMessage.warning('请上传5MB以内的JPG、PNG或WebP图片')
        continue
      }
      const response = await uploadReviewImage(file)
      if (response.code === SUCCESS_CODE && response.data) images.value.push(response.data)
    }
  } finally {
    uploadLoading.value = false
    input.value = ''
  }
}

function removeImage(index: number) {
  images.value.splice(index, 1)
}

async function submit() {
  if (!selectedOrder.value) return
  submitting.value = true
  try {
    const response = await submitOrderReview({
      orderId: selectedOrder.value.id,
      rating: overallRating.value,
      content: content.value,
      images: images.value,
      items: dishRatings.value.map(({ orderDetailId, dishId, rating }) => ({
        orderDetailId,
        dishId,
        rating,
      })),
    })
    if (response.code === SUCCESS_CODE) {
      ElMessage.success('评价提交成功')
      dialogVisible.value = false
      await Promise.all([loadPending(), loadMine()])
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => void loadPending())
</script>

<template>
  <section class="review-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">YOUR FEEDBACK MATTERS</p>
        <h1>评价晒单</h1>
        <p>分享用餐体验，让每一份美味都更进一步</p>
      </div>
      <el-icon class="heading-icon"><ChatLineRound /></el-icon>
    </header>

    <nav class="review-tabs">
      <button :class="{ active: activeTab === 'pending' }" type="button" @click="changeTab('pending')">
        待评价 <span>{{ pendingOrders.length }}</span>
      </button>
      <button :class="{ active: activeTab === 'mine' }" type="button" @click="changeTab('mine')">我的评价</button>
    </nav>

    <div v-loading="loading" class="review-list">
      <template v-if="activeTab === 'pending'">
        <article v-for="order in pendingOrders" :key="order.id" class="review-card">
          <div class="review-card-heading">
            <div>
              <strong>订单 {{ order.number }}</strong>
              <span>{{ order.orderTime }}</span>
            </div>
            <el-button type="primary" round @click="startReview(order)">去评价</el-button>
          </div>
          <div class="order-dishes">
            <span v-for="detail in order.orderDetails ?? []" :key="detail.id">
              {{ detail.name }}
            </span>
          </div>
          <div class="order-amount">订单金额 <strong>￥{{ Number(order.amount).toFixed(2) }}</strong></div>
        </article>
        <el-empty v-if="!loading && pendingOrders.length === 0" description="暂时没有待评价订单" />
      </template>

      <template v-else>
        <article v-for="review in reviews" :key="review.id" class="review-card mine-card">
          <div class="review-card-heading">
            <div>
              <strong>订单 {{ review.orderNumber }}</strong>
              <span>{{ review.createTime }}</span>
            </div>
            <el-rate :model-value="review.rating" disabled />
          </div>
          <p class="review-content">{{ review.content || '这位用户没有填写文字评价' }}</p>
          <div v-if="review.items?.length" class="dish-score-list">
            <span v-for="item in review.items" :key="`${item.dishId}-${item.dishName}`">
              {{ item.dishName }} <el-rate :model-value="item.rating" disabled size="small" />
            </span>
          </div>
          <div v-if="review.images?.length" class="review-images">
            <img v-for="image in review.images" :key="image.imageUrl" :src="getImageUrl(image.imageUrl)" alt="评价图片" />
          </div>
        </article>
        <el-empty v-if="!loading && reviews.length === 0" description="还没有评价记录" />
      </template>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="min(680px, calc(100vw - 32px))" append-to-body>
      <div class="review-form">
        <label class="overall-rating">
          <strong>整体用餐体验</strong>
          <el-rate v-model="overallRating" show-text :texts="['很差', '较差', '一般', '不错', '非常满意']" />
        </label>
        <div v-if="dishRatings.length" class="dish-rating-section">
          <strong>菜品评分</strong>
          <div v-for="item in dishRatings" :key="`${item.orderDetailId}-${item.dishId}`" class="dish-rating-row">
            <span>{{ item.dishName }}</span>
            <el-rate v-model="item.rating" />
          </div>
        </div>
        <el-input v-model="content" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="写下你的真实感受吧" />
        <div class="upload-heading">
          <span>晒图（最多9张）</span>
          <el-button :loading="uploadLoading" :icon="UploadFilled" plain @click="chooseImages">上传图片</el-button>
          <input ref="fileInput" class="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple @change="onFilesSelected" />
        </div>
        <div class="review-images">
          <div v-for="(image, index) in images" :key="image" class="image-preview">
            <img :src="getImageUrl(image)" alt="待上传评价图片" />
            <button type="button" aria-label="删除图片" @click="removeImage(index)">×</button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交评价</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.review-page { padding-bottom: 40px; color: #24372e; }
.page-heading { display: flex; align-items: center; justify-content: space-between; padding: 24px 0 20px; }
.page-heading h1 { margin: 4px 0 6px; color: #153d31; font-size: 28px; }
.page-heading p:last-child { margin: 0; color: #7a8983; }
.eyebrow { margin: 0; color: #4a9a79; font-size: 11px; font-weight: 700; letter-spacing: .14em; }
.heading-icon { color: #2e8b68; font-size: 30px; }
.review-tabs { display: flex; gap: 8px; padding: 4px; border-radius: 12px; background: #eaf1ed; }
.review-tabs button { flex: 1; min-height: 42px; border: 0; border-radius: 9px; background: transparent; color: #68786f; cursor: pointer; }
.review-tabs button.active { background: #fff; color: #16785b; font-weight: 700; box-shadow: 0 2px 8px rgb(30 72 51 / 8%); }
.review-tabs span { margin-left: 4px; color: #8d9b93; }
.review-list { min-height: 220px; padding-top: 14px; }
.review-card { margin-bottom: 12px; padding: 18px; border: 1px solid #edf1ee; border-radius: 15px; background: #fff; box-shadow: 0 8px 25px rgb(35 80 59 / 5%); }
.review-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.review-card-heading > div { display: grid; gap: 6px; }
.review-card-heading strong { color: #30483b; }
.review-card-heading span { color: #8c9992; font-size: 12px; }
.order-dishes { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 12px; }
.order-dishes span { padding: 6px 10px; border-radius: 15px; background: #f2f6f3; color: #67776d; font-size: 12px; }
.order-amount { color: #7d8b83; font-size: 13px; }
.order-amount strong { color: #d16b43; }
.review-content { color: #627168; line-height: 1.7; }
.dish-score-list { display: grid; gap: 8px; margin: 12px 0; color: #6c7b72; }
.dish-score-list span { display: flex; align-items: center; gap: 8px; }
.review-images { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 12px; }
.review-images img, .image-preview img { width: 82px; height: 82px; border-radius: 9px; object-fit: cover; }
.review-form { display: grid; gap: 20px; }
.overall-rating, .dish-rating-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.dish-rating-section { display: grid; gap: 12px; }
.dish-rating-section > strong { color: #34483d; }
.dish-rating-row { padding: 9px 0; border-bottom: 1px solid #eff2f0; color: #68776e; }
.upload-heading { display: flex; align-items: center; gap: 12px; color: #66776d; }
.image-preview { position: relative; }
.image-preview button { position: absolute; top: -7px; right: -7px; width: 22px; height: 22px; border: 0; border-radius: 50%; background: #d85c4e; color: #fff; cursor: pointer; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
@media (max-width: 600px) { .review-card { padding: 14px; } .review-card-heading { align-items: flex-start; } .overall-rating, .dish-rating-row { align-items: flex-start; flex-direction: column; } }
</style>
