<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getImageUrl } from '@/api/request'
import { getOrderHistory, repetitionOrder, reminderOrder } from '@/api/modules/order'
import { cleanShoppingCart } from '@/api/modules/cart'
import { SUCCESS_CODE } from '@/constants/business'
import { cancelReservation, listReservations, type Reservation } from '@/api/modules/meal'
import type { OrderHistoryItem } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { getOvertime, statusWord, sumOrderDetail } from '@/utils/order'

/**
 * 历史订单（对应小程序 `pages/historyOrder/historyOrder`）
 *
 * 与源码一致：
 *  - 即时订单保留原有筛选，另增加独立的预约订单标签。
 *  - 即时订单 tab 下标 → status：0 → ''，1 → 1，2 → 6
 *  - 请求 `GET /user/order/historyOrders`，参数 `{ page, pageSize: 10, status }`
 *  - 数据显示：下单时间 + 状态文案、横向菜品缩略图、合计金额与件数
 *  - 按钮：再来一单（都显示）、去支付（status === 1 且未超时）、催单（status === 2）
 *  - 再来一单：先清空购物车，再调 `POST /user/order/repetition/{id}`，成功后回首页
 *
 * 修正的源码缺陷：
 *  - 源码「催单」按钮没有对应接口调用的错误处理，且 `handleReminder` 只弹提示不刷新；
 *    这里保持提示，但补上错误提示（由请求层统一处理）。
 */
const router = useRouter()
const cart = useCartStore()

const TAB_BARS = ['全部订单', '待付款', '退款', '预约订单']
const PAGE_SIZE = 10

const tabIndex = ref(0)
const status = ref<number | ''>('')
const list = ref<OrderHistoryItem[]>([])
const reservationList = ref<Reservation[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)

function statusText(item: OrderHistoryItem): string {
  return statusWord(Number(item.status), getOvertime(item.orderTime ?? undefined))
}

function canPay(item: OrderHistoryItem): boolean {
  return Number(item.status) === 1 && getOvertime(item.orderTime ?? undefined) > 0
}

function canReminder(item: OrderHistoryItem): boolean {
  return Number(item.status) === 2
}

const hasMore = () =>
  tabIndex.value === 3
    ? reservationList.value.length >= page.value * PAGE_SIZE
    : list.value.length < Number(total.value)

async function loadList(reset = false) {
  if (loading.value) return
  loading.value = true
  if (reset) {
    page.value = 1
    if (tabIndex.value === 3) reservationList.value = []
    else list.value = []
  }
  try {
    if (tabIndex.value === 3) {
      const response = await listReservations(page.value, PAGE_SIZE)
      if (response.code === SUCCESS_CODE) {
        const records = response.data ?? []
        reservationList.value = reset ? records : [...reservationList.value, ...records]
      }
      return
    }
    const res = await getOrderHistory({
      page: page.value,
      pageSize: PAGE_SIZE,
      status: status.value === '' ? '' : status.value,
    })
    if (res.code === SUCCESS_CODE && res.data) {
      const records = (res.data.records ?? []) as OrderHistoryItem[]
      list.value = reset ? [...records] : [...list.value, ...records]
      total.value = Number(res.data.total ?? 0)
    }
  } catch {
    // 请求层已提示
  } finally {
    loading.value = false
  }
}

function changeTab(index: number) {
  if (index === tabIndex.value) return
  tabIndex.value = index
  status.value = index === 1 ? 1 : index === 2 ? 6 : ''
  void loadList(true)
}

function loadMore() {
  if (!hasMore()) return
  page.value += 1
  void loadList()
}

function goDetail(id?: number) {
  if (id === undefined) return
  router.push(`/order/detail/${id}`)
}

function goPay(item: OrderHistoryItem) {
  router.push(`/pay/${item.id}`)
}

function reservationStatusText(status: number) {
  return ['待取餐', '已取餐', '已取消', '超时未取'][status] ?? '状态未知'
}

async function cancelMyReservation(id: number) {
  try {
    const response = await cancelReservation(id)
    if (response.code === SUCCESS_CODE) {
      await loadList(true)
    }
  } catch {
    // 请求层已提示
  }
}

async function oneMoreOrder(id?: number) {
  if (id === undefined) return
  await cleanShoppingCart()
  try {
    const res = await repetitionOrder(id)
    if (res.code === SUCCESS_CODE) {
      await cart.fetchCart()
      ElMessage.success('已加入购物车')
      await router.replace('/index')
    }
  } catch {
    // 请求层已提示
  }
}

async function handleReminder(id?: number) {
  if (id === undefined) return
  try {
    const res = await reminderOrder(id)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success('您的催单信息已发出！')
    }
  } catch {
    // 请求层已提示
  }
}

watch(tabIndex, () => {
  /* changeTab 内已处理 */
})

onMounted(() => {
  void loadList(true)
})
</script>

<template>
  <div class="history-page">
    <AppNavBar title="历史订单" @back="router.replace('/my')" />

    <div class="tabs">
      <button
        v-for="(tab, index) in TAB_BARS"
        :key="tab"
        class="tab"
        :class="{ active: tabIndex === index }"
        type="button"
        @click="changeTab(index)"
      >
        {{ tab }}
      </button>
    </div>

    <div v-loading="loading" class="history-body">
      <template v-if="tabIndex === 3 && reservationList.length > 0">
        <div v-for="reservation in reservationList" :key="`reservation-${reservation.id}`" class="order-card">
          <div class="order-head">
            <span class="order-time">{{ reservation.mealDate }} · {{ reservation.mealPeriod === 1 ? '午餐' : '晚餐' }}</span>
            <span class="order-status" :class="{ pending: reservation.status === 0 }">
              预约 · {{ reservationStatusText(reservation.status) }}
            </span>
          </div>
          <div class="reservation-items">
            <span v-for="item in reservation.items" :key="item.id">{{ item.itemName }} × {{ item.quantity }}</span>
          </div>
          <div class="order-actions">
            <span class="order-amount">￥{{ Number(reservation.totalAmount).toFixed(2) }}</span>
            <button v-if="reservation.status === 0" class="btn warn" type="button" @click="cancelMyReservation(reservation.id)">
              取消预约
            </button>
          </div>
        </div>
        <div v-if="hasMore()" class="load-more">
          <el-button :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </template>

      <template v-else-if="tabIndex !== 3 && list.length > 0">
        <div v-for="item in list" :key="item.id" class="order-card">
          <div class="order-head">
            <span class="order-time">{{ item.orderTime }}</span>
            <span class="order-status" :class="{ pending: Number(item.status) === 2 }">
              {{ statusText(item) }}
            </span>
          </div>

          <div class="order-body" @click="goDetail(item.id)">
            <div class="order-thumbs">
              <div v-for="(d, i) in (item.orderDetailList ?? []).slice(0, 4)" :key="i" class="thumb">
                <img v-image-fallback :src="getImageUrl(d.image)" :alt="String(d.name ?? '')" />
                <span class="thumb-name ellipsis">{{ d.name }}</span>
              </div>
            </div>
            <div class="order-sum">
              <span class="order-amount"
                >￥{{ fenToYuan(sumOrderDetail(item.orderDetailList).totalFen).toFixed(2) }}</span
              >
              <span class="order-count"
                >共 {{ sumOrderDetail(item.orderDetailList).count }} 件</span
              >
            </div>
          </div>

          <div class="order-actions">
            <button class="btn" type="button" @click="oneMoreOrder(item.id)">再来一单</button>
            <button v-if="canPay(item)" class="btn warn" type="button" @click="goPay(item)">
              去支付
            </button>
            <button
              v-if="canReminder(item)"
              class="btn warn"
              type="button"
              @click="handleReminder(item.id)"
            >
              催单
            </button>
          </div>
        </div>

        <div v-if="hasMore()" class="load-more">
          <el-button :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
        <p v-else class="no-more">没有更多了</p>
      </template>

      <EmptyState v-else-if="!loading" :text="tabIndex === 3 ? '暂无预约订单' : '暂无订单'" image="/static/no_order.png" />
    </div>
  </div>
</template>

<style scoped>
.history-page {
  min-height: 100vh;
  background: var(--sky-bg);
}

.tabs {
  display: flex;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

.tab {
  position: relative;
  flex: 1;
  height: 44px;
  border: 0;
  background: transparent;
  color: var(--sky-text-sub);
  font-size: 14px;
  cursor: pointer;
}

.tab.active {
  color: var(--sky-text);
  font-weight: 600;
}

.tab.active::after {
  position: absolute;
  bottom: 0;
  left: 50%;
  width: 16px;
  height: 3px;
  border-radius: 2px;
  background: var(--sky-accent);
  content: '';
  transform: translateX(-50%);
}

.history-body {
  padding: 10px;
}

.order-card {
  padding: 12px;
  margin-bottom: 10px;
  background: #fff;
  border-radius: 8px;
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--sky-border);
}

.order-time {
  color: var(--sky-text);
  font-size: 13px;
}

.order-status {
  color: var(--sky-text-sub);
  font-size: 13px;
}

.order-status.pending {
  color: var(--sky-accent);
}

.order-body {
  padding: 10px 0;
  cursor: pointer;
}

.order-thumbs {
  display: flex;
  gap: 10px;
  overflow-x: auto;
}

.thumb {
  flex: 0 0 56px;
  width: 56px;
  text-align: center;
}

.thumb img {
  width: 56px;
  height: 56px;
  border-radius: 6px;
  object-fit: cover;
  background: #f2f2f2;
}

.thumb-name {
  display: block;
  margin-top: 2px;
  color: var(--sky-text-weak);
  font-size: 11px;
}

.order-sum {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

.order-amount {
  color: var(--sky-text);
  font-size: 16px;
  font-weight: 600;
}

.order-count {
  color: var(--sky-text-weak);
  font-size: 12px;
}

.order-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.reservation-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.reservation-items span {
  padding: 5px 9px;
  border-radius: 14px;
  background: #f2f6f3;
}

.btn {
  height: 30px;
  padding: 0 16px;
  border: 1px solid #e5e4e4;
  border-radius: 15px;
  background: #fff;
  color: var(--sky-text);
  font-size: 13px;
  cursor: pointer;
}

.btn.warn {
  border-color: var(--sky-accent);
  background: var(--sky-accent);
  font-weight: 600;
}

.load-more {
  padding: 12px 0;
  text-align: center;
}

.no-more {
  padding: 12px 0;
  color: var(--sky-text-weak);
  font-size: 12px;
  text-align: center;
}
</style>
