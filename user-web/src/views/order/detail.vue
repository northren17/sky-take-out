<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import { getImageUrl } from '@/api/request'
import {
  cancelOrder,
  getOrderDetail,
  reminderOrder,
  repetitionOrder,
} from '@/api/modules/order'
import { cleanShoppingCart } from '@/api/modules/cart'
import { SUCCESS_CODE, PAY_METHOD } from '@/constants/business'
import type { OrderDetailVo } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'
import { statusWordDetail } from '@/utils/order'

/**
 * 订单详情（对应小程序 `pages/details/index`）
 *
 * 与源码一致：
 *  - 状态标题由另一套 `statusWord` 生成（措辞与历史订单页不同）。
 *  - 待付款时展示 15 分钟倒计时；超时会自动取消订单。
 *  - 按钮显示条件：
 *      取消订单：status ∈ {1,2,3,4} 且未超时
 *      立即支付：status === 1 且未超时
 *      催单：    status === 2
 *      申请退款：status === 5（只弹「请联系商家进行退款！」）
 *      再来一单：status !== 7
 *  - 取消订单仅允许 status ∈ {1,2}，否则提示「请联系商家进行取消！」
 *
 * 修正的源码缺陷：
 *  - 源码 `status.vue` 用 `$emit` 当渲染函数，导致**状态标题永远为空**；这里正常渲染。
 *  - 源码详情页未绑定 `@handleCancel` / `@oneMoreOrder`，两个按钮点了没反应；
 *    这里正常绑定。
 *  - 源码 `$refs.status.$refs.commonPopup` 的 ref 路径错误会抛 TypeError；
 *    这里改用 ElMessageBox / ElMessage。
 */
const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const shop = useShopStore()
const checkout = useCheckoutStore()

const orderId = computed(() => String(route.params.orderId ?? ''))
const detail = ref<OrderDetailVo | null>(null)
const loading = ref(false)
const timeout = ref(false)
const remain = ref('')
const acting = ref(false)

let timer: number | undefined

const statusText = computed(() => statusWordDetail(Number(detail.value?.status), timeout.value))
const isUnPaid = computed(() => Number(detail.value?.status) === 1)
const payMethodText = computed(() =>
  Number(detail.value?.payMethod) === PAY_METHOD.WECHAT ? '微信' : '支付宝',
)
const deliveryTimeText = computed(() =>
  Number(detail.value?.deliveryStatus) === 1
    ? '立即送出'
    : String(detail.value?.estimatedDeliveryTime ?? ''),
)

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/** 15 分钟支付倒计时；超时自动取消（对齐源码 `runTimeBack`） */
function tick() {
  if (!isUnPaid.value) {
    stopTimer()
    remain.value = ''
    return
  }
  const orderTime = detail.value?.orderTime
  if (!orderTime) return
  const end = Date.parse(String(orderTime).replace(/-/g, '/'))
  const msec = 15 * 60 * 1000 - (Date.now() - end)
  if (msec <= 0) {
    timeout.value = true
    remain.value = '00:00'
    stopTimer()
    void doCancel(true)
    return
  }
  const min = String(Math.floor((msec / 1000 / 60) % 60)).padStart(2, '0')
  const sec = String(Math.floor((msec / 1000) % 60)).padStart(2, '0')
  remain.value = `${min}:${sec}`
}

async function loadDetail() {
  loading.value = true
  try {
    const res = await getOrderDetail(orderId.value)
    if (res.code === SUCCESS_CODE && res.data) {
      detail.value = res.data
      timeout.value = false
      tick()
    }
  } catch {
    // 请求层已提示
  } finally {
    loading.value = false
  }
}

async function doCancel(silent = false) {
  if (!detail.value) return
  if (!silent) {
    try {
      await ElMessageBox.confirm('确定要取消这个订单吗？', '取消订单', {
        type: 'warning',
        confirmButtonText: '确定取消',
        cancelButtonText: '再想想',
      })
    } catch {
      return
    }
  }
  acting.value = true
  try {
    const res = await cancelOrder(orderId.value)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success('您的订单已取消！')
      await loadDetail()
    }
  } catch {
    // 请求层已提示
  } finally {
    acting.value = false
  }
}

/** 源码：只有 status 为 1 或 2 才允许取消，否则提示联系商家 */
function handleCancel() {
  const status = Number(detail.value?.status)
  if (status === 1 || status === 2) {
    void doCancel()
    return
  }
  void ElMessageBox.alert('请联系商家进行取消！', '提示', { confirmButtonText: '知道了' })
}

async function handlePay() {
  if (!detail.value) return
  // 把订单信息交给支付页：源码是 setOrderData({orderNumber, orderAmount, orderTime})
  checkout.setOrderData({
    id: Number(detail.value.id ?? 0),
    orderNumber: String(detail.value.number ?? ''),
    orderAmount: Number(detail.value.amount ?? 0),
    orderTime: String(detail.value.orderTime ?? ''),
  })
  await router.replace(`/pay/${orderId.value}`)
}

async function handleReminder() {
  acting.value = true
  try {
    const res = await reminderOrder(orderId.value)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success('您的催单信息已发出！')
    }
  } catch {
    // 请求层已提示
  } finally {
    acting.value = false
  }
}

function handleRefund() {
  void ElMessageBox.alert('请联系商家进行退款！', '提示', { confirmButtonText: '知道了' })
}

async function oneMoreOrder() {
  acting.value = true
  try {
    await cleanShoppingCart()
    const res = await repetitionOrder(orderId.value)
    if (res.code === SUCCESS_CODE) {
      await cart.fetchCart()
      ElMessage.success('已加入购物车')
      await router.replace('/index')
    }
  } catch {
    // 请求层已提示
  } finally {
    acting.value = false
  }
}

function callShop() {
  window.location.href = `tel:${shop.phone}`
}

onMounted(async () => {
  await shop.fetchStatus()
  await loadDetail()
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="detail-page">
    <AppNavBar title="订单详情" @back="router.replace('/history')" />

    <div v-loading="loading" class="detail-body">
      <!-- 状态 -->
      <section class="card">
        <div class="status-head">
          <h2 class="status-title">{{ statusText }}</h2>
          <p v-if="isUnPaid && !timeout" class="status-countdown">
            等待支付：<span class="remain">{{ remain }}</span>
          </p>
        </div>
        <div class="status-actions">
          <button
            v-if="!timeout && [1, 2, 3, 4].includes(Number(detail?.status))"
            class="btn"
            type="button"
            :disabled="acting"
            @click="handleCancel"
          >
            取消订单
          </button>
          <button
            v-if="!timeout && Number(detail?.status) === 1"
            class="btn warn"
            type="button"
            :disabled="acting"
            @click="handlePay"
          >
            立即支付
          </button>
          <button
            v-if="Number(detail?.status) === 2"
            class="btn warn"
            type="button"
            :disabled="acting"
            @click="handleReminder"
          >
            催单
          </button>
          <button
            v-if="Number(detail?.status) === 5"
            class="btn"
            type="button"
            @click="handleRefund"
          >
            申请退款
          </button>
          <button
            v-if="Number(detail?.status) !== 7"
            class="btn"
            type="button"
            :disabled="acting"
            @click="oneMoreOrder"
          >
            再来一单
          </button>
        </div>
        <p v-if="!timeout && Number(detail?.status) === 1" class="status-tip">
          请在 15 分钟内完成支付，超时将自动取消。
        </p>
        <p v-if="Number(detail?.status) === 6" class="status-tip">
          您的订单已<strong>取消</strong>。
        </p>
      </section>

      <!-- 菜品 -->
      <section class="card">
        <h3 class="card-title">苍穹餐厅</h3>
        <div v-for="(d, i) in detail?.orderDetailList ?? []" :key="i" class="dish-line">
          <img v-image-fallback class="dish-img" :src="getImageUrl(d.image)" :alt="String(d.name ?? '')" />
          <div class="dish-main">
            <p class="dish-name ellipsis">{{ d.name }}</p>
            <p v-if="d.dishFlavor" class="dish-flavor">{{ d.dishFlavor }}</p>
            <p class="dish-price">
              × {{ d.number }}
              <span class="dish-amount"
                >￥{{ fenToYuan(Number(d.number) * Number(d.amount)).toFixed(2) }}</span
              >
            </p>
          </div>
        </div>

        <div class="fee-lines">
          <div class="fee-line">
            <span>打包费</span>
            <span>￥{{ fenToYuan(detail?.packFee ?? detail?.packAmount).toFixed(2) }}</span>
          </div>
          <div class="fee-line">
            <span>配送费</span>
            <span>￥{{ fenToYuan(detail?.deliveryFee ?? 6).toFixed(2) }}</span>
          </div>
          <div v-if="Number(detail?.couponDiscount ?? 0) > 0" class="fee-line">
            <span>{{ detail?.couponNameSnapshot || '优惠券' }}</span>
            <span>-￥{{ fenToYuan(detail?.couponDiscount).toFixed(2) }}</span>
          </div>
          <div class="fee-line total">
            <span>合计</span>
            <span class="price"
              ><span class="symbol">￥</span
              >{{ fenToYuan(detail?.amount).toFixed(2) }}</span
            >
          </div>
        </div>
      </section>

      <!-- 联系商家 -->
      <section class="card contact" @click="callShop">
        <img src="/static/icon.png" alt="" />
        <span>联系商家</span>
      </section>

      <!-- 配送信息 -->
      <section class="card">
        <div class="info-line">
          <span class="info-label">期望时间</span>
          <span>{{ deliveryTimeText }}</span>
        </div>
        <div class="info-line">
          <span class="info-label">配送地址</span>
          <div class="info-value">
            <p class="info-name">{{ detail?.consignee }} {{ detail?.phone }}</p>
            <p class="info-addr">{{ detail?.address }}</p>
          </div>
        </div>
      </section>

      <!-- 订单信息 -->
      <section class="card">
        <div class="info-line">
          <span class="info-label">订单号码</span>
          <span>{{ detail?.number }}</span>
        </div>
        <div class="info-line">
          <span class="info-label">订单时间</span>
          <span>{{ detail?.orderTime }}</span>
        </div>
        <div class="info-line">
          <span class="info-label">支付方式</span>
          <span>{{ payMethodText }}</span>
        </div>
        <div class="info-line">
          <span class="info-label">餐具数量</span>
          <span>{{ detail?.tablewareNumber ?? 0 }}</span>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  min-height: 100vh;
  padding-bottom: 16px;
  background: var(--sky-bg);
}

.detail-body {
  padding: 10px;
}

.card {
  padding: 12px;
  margin-bottom: 10px;
  background: #fff;
  border-radius: 8px;
}

.card-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

/* 状态 */
.status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.status-countdown {
  margin: 0;
  color: var(--sky-text-sub);
  font-size: 12px;
}

.remain {
  color: var(--sky-primary);
  font-weight: 600;
}

.status-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.btn {
  height: 30px;
  padding: 0 14px;
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

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.status-tip {
  margin: 10px 0 0;
  color: var(--sky-text-weak);
  font-size: 12px;
}

/* 菜品 */
.dish-line {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--sky-border);
}

.dish-img {
  flex: 0 0 52px;
  width: 52px;
  height: 52px;
  border-radius: 6px;
  object-fit: cover;
  background: #f2f2f2;
}

.dish-main {
  flex: 1;
  min-width: 0;
}

.dish-name {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.dish-flavor {
  margin: 2px 0 0;
  color: var(--sky-text-weak);
  font-size: 12px;
}

.dish-price {
  display: flex;
  justify-content: space-between;
  margin: 4px 0 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.dish-amount {
  color: var(--sky-price);
  font-weight: 600;
}

.fee-lines {
  margin-top: 8px;
}

.fee-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.fee-line.total {
  padding-top: 8px;
  margin-top: 4px;
  border-top: 1px solid var(--sky-border);
  color: var(--sky-text);
  font-weight: 600;
}

.fee-line.total .price {
  font-size: 18px;
}

.fee-line.total .symbol {
  font-size: 12px;
}

/* 联系商家 */
.contact {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--sky-text);
  font-size: 14px;
  cursor: pointer;
}

.contact img {
  width: 16px;
  height: 16px;
}

/* 信息行 */
.info-line {
  display: flex;
  gap: 12px;
  padding: 6px 0;
  font-size: 13px;
}

.info-label {
  flex: 0 0 64px;
  color: var(--sky-text-weak);
}

.info-value {
  flex: 1;
}

.info-name {
  margin: 0;
  font-weight: 600;
}

.info-addr {
  margin: 2px 0 0;
  color: var(--sky-text-sub);
}
</style>
