<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { DELIVERY_FEE, getImageUrl } from '@/api/request'
import { getAddressBookDefault } from '@/api/modules/address'
import { submitOrder } from '@/api/modules/order'
import { getMyCoupons, type UserCoupon } from '@/api/modules/coupon'
import { SUCCESS_CODE, DELIVERY_STATUS, PAY_METHOD, TABLEWARE_STATUS } from '@/constants/business'
import type { AddressBook } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 提交订单（对应小程序 `pages/order/index`）
 *
 * 购物车金额及订单价格字段按项目现有接口使用元；服务端会重新读取购物车、
 * 配送费、打包费和优惠规则计算最终应付金额，不信任此页提交的 amount。
 */
const router = useRouter()
const cart = useCartStore()
const checkout = useCheckoutStore()

const address = ref<AddressBook | null>(null)
const submitting = ref(false)
const availableCoupons = ref<UserCoupon[]>([])
const selectedCouponId = ref<number>()
const selectedCoupon = computed(() =>
  availableCoupons.value.find((coupon) => coupon.id === selectedCouponId.value),
)

/** 备注：本地绑定，进入时用 store 里的值（源码从 `remarkData()` 读） */
const remark = ref(checkout.remark)
/** 餐具选择 */
const tablewareOption = ref<string>('无需餐具')
const tablewareNumber = ref(0)
const tablewareStatus = ref<number>(TABLEWARE_STATUS.SPECIFIC)

/** 送达时间 */
const arrivalTime = ref('')
const deliveryImmediately = ref(true)

const TABLEWARE_OPTIONS = ['依据餐量提供', '无需餐具', ...Array.from({ length: 10 }, (_, i) => String(i + 1))]

/**
 * 仅用于下单前展示的未抵扣估算。最终订单金额由服务端根据购物车重新计算。
 */
/** 菜品合计（元） */
const itemsTotal = computed(() => cart.totalFen)
/** 打包费（元）：源码按「每件 1 元」处理 */
const packAmount = computed(() => cart.totalCount)
/** 配送费（元）：源码硬编码 6 元 */
const deliveryFee = computed(() => DELIVERY_FEE)
/** 合计（元） */
const totalAmount = computed(() => itemsTotal.value + packAmount.value + deliveryFee.value)

const addressText = computed(() => checkout.fullAddress)

/** 生成「一小时后」的时间，对应源码 `getHarfAnOur()` */
function hourLater(): string {
  const d = new Date(Date.now() + 3600 * 1000)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

/** 源码 `presentFormat()`：当前时间 + 1 小时，格式 `YYYY-MM-DD HH:mm:ss` */
function presentFormat(): string {
  const d = new Date(Date.now() + 3600 * 1000)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`
}

/** 源码 `dateFormat(isTomorrow, 'HH:mm')`：拼成 `YYYY-MM-DD HH:mm:00` */
function dateFormat(tomorrow: boolean, time: string): string {
  const d = new Date()
  if (tomorrow) d.setDate(d.getDate() + 1)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${time}:00`
}

/** 可选时段：立即派送 + 30 分钟粒度（源码 popright 的简化实现） */
const timeOptions = computed(() => {
  const list: string[] = ['立即派送']
  for (let h = 9; h <= 23; h++) {
    list.push(`${String(h).padStart(2, '0')}:00`)
    if (h < 23) list.push(`${String(h).padStart(2, '0')}:30`)
  }
  return list
})

function pickTime(value: string) {
  if (value === '立即派送') {
    deliveryImmediately.value = true
    arrivalTime.value = hourLater()
  } else {
    deliveryImmediately.value = false
    arrivalTime.value = value
  }
}

/**
 * 餐具选择 -> `tablewareStatus` / `tablewareNumber`
 * 对齐源码 `handlePiker()` 的语义：
 *  - 「依据餐量提供」→ status = 1，number = 菜品总件数
 *  - 「无需餐具」    → status = 0，number = 0
 *  - 具体数量 `n`    → status = 0，number = n
 * （源码那段 `a !== 'A' || a !== 'B'` 恒真，导致文案总被拼上「份」，这里按语义修正。）
 */
watch(tablewareOption, (value) => {
  if (value === '依据餐量提供') {
    tablewareStatus.value = TABLEWARE_STATUS.BY_MEAL
    tablewareNumber.value = cart.totalCount
    return
  }
  if (value === '无需餐具') {
    tablewareStatus.value = TABLEWARE_STATUS.SPECIFIC
    tablewareNumber.value = 0
    return
  }
  tablewareStatus.value = TABLEWARE_STATUS.SPECIFIC
  tablewareNumber.value = Number(value) || 0
})

async function loadAddress() {
  // 优先用地址页选好的地址（源码从 Vuex `addressData()` 读）
  if (checkout.address) {
    address.value = checkout.address
    return
  }
  try {
    const res = await getAddressBookDefault()
    if (res.code === SUCCESS_CODE && res.data) {
      checkout.setAddress(res.data)
      address.value = res.data
    }
  } catch {
    // 没有默认地址时保持为空，页面会提示"请选择收货地址"
  }
}

async function loadCoupons() {
  try {
    const response = await getMyCoupons(1, 100, 0)
    if (response.code === SUCCESS_CODE && response.data) {
      const now = Date.now()
      availableCoupons.value = (response.data.records ?? []).filter(
        (coupon) => Date.parse(coupon.validStartTime.replace(' ', 'T')) <= now,
      )
    }
  } catch {
    // Request layer reports the failure; ordering can continue without a coupon.
  }
}

function goAddress() {
  checkout.setAddressBackPath('/order')
  router.push('/address')
}

function goRemark() {
  router.push('/remark')
}

async function submit() {
  if (!address.value) {
    ElMessage.warning('请选择收货地址')
    return
  }
  if (cart.isEmpty) {
    ElMessage.warning('购物车是空的')
    return
  }
  submitting.value = true
  try {
    const res = await submitOrder({
      addressBookId: Number(address.value.id),
      amount: totalAmount.value,
      deliveryStatus: deliveryImmediately.value
        ? DELIVERY_STATUS.IMMEDIATELY
        : DELIVERY_STATUS.SCHEDULED,
      estimatedDeliveryTime: deliveryImmediately.value
        ? presentFormat()
        : dateFormat(false, arrivalTime.value),
      packAmount: packAmount.value,
      payMethod: PAY_METHOD.WECHAT,
      userCouponId: selectedCouponId.value,
      remark: remark.value,
      tablewareNumber: tablewareNumber.value,
      tablewareStatus: tablewareStatus.value,
    })
    if (res.code === SUCCESS_CODE && res.data) {
      checkout.setOrderData(res.data)
      checkout.setRemark('')
      await router.replace(`/pay/${res.data.id}`)
      return
    }
    ElMessage.error(res.msg || '操作失败')
  } catch {
    // 请求层已提示
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  arrivalTime.value = hourLater()
  await cart.fetchCart()
  await loadAddress()
  await loadCoupons()
  // 从备注页回来后同步一次
  remark.value = checkout.remark
})
</script>

<template>
  <div class="order-page">
    <AppNavBar title="提交订单" @back="router.replace('/index')" />

    <div class="order-body">
      <!-- 收货地址 -->
      <section class="card address-card" @click="goAddress">
        <template v-if="address">
          <div class="addr-line">
            <span v-if="address.label" class="addr-tag">{{ address.label }}</span>
            <span class="addr-text">{{ addressText }}</span>
          </div>
          <div class="addr-name">
            <span>{{ address.consignee }}</span>
            <span class="addr-phone">{{ address.phone }}</span>
          </div>
        </template>
        <div v-else class="addr-empty">请选择收货地址</div>
        <img class="addr-arrow" src="/static/toRight.png" alt="" />
      </section>

      <!-- 送达时间 -->
      <section class="card">
        <el-select
          :model-value="deliveryImmediately ? '立即派送' : arrivalTime"
          class="full"
          @change="pickTime"
        >
          <el-option v-for="t in timeOptions" :key="t" :label="t" :value="t" />
        </el-select>
        <p class="tip">因配送订单较多，送达时间可能波动</p>
      </section>

      <!-- 订单明细 -->
      <section class="card">
        <h3 class="card-title">订单明细</h3>
        <div v-for="item in cart.items" :key="item.id" class="dish-line">
          <img v-image-fallback class="dish-line-img" :src="getImageUrl(item.image)" :alt="String(item.name ?? '')" />
          <div class="dish-line-main">
            <p class="dish-line-name ellipsis">{{ item.name }}</p>
            <p v-if="item.dishFlavor" class="dish-line-flavor">{{ item.dishFlavor }}</p>
            <p class="dish-line-price">
              <span class="symbol">￥</span>{{ fenToYuan(item.amount).toFixed(2) }}
              <span class="dish-line-count">× {{ item.number }}</span>
            </p>
          </div>
        </div>
        <EmptyState v-if="cart.isEmpty" text="购物车是空的" />
      </section>

      <section class="card">
        <div class="fee-line">
          <span>优惠券</span>
          <el-select v-model="selectedCouponId" clearable placeholder="不使用优惠券">
            <el-option
              v-for="coupon in availableCoupons"
              :key="coupon.id"
              :label="`${coupon.couponNameSnapshot} · ${coupon.couponTypeSnapshot === 2
                ? `打${(coupon.discountRateSnapshot * 10).toFixed(1)}折`
                : `减￥${coupon.discountAmountSnapshot.toFixed(2)}`}`"
              :value="coupon.id"
            />
          </el-select>
        </div>
        <p v-if="selectedCoupon" class="tip">优惠门槛和适用商品由服务端校验，最终抵扣金额以订单为准。</p>
      </section>

      <!-- 费用 -->
      <section class="card">
        <div class="fee-line">
          <span>打包费</span>
          <span>￥{{ fenToYuan(packAmount).toFixed(2) }}</span>
        </div>
        <div class="fee-line">
          <span>配送费</span>
          <span>￥{{ fenToYuan(deliveryFee).toFixed(2) }}</span>
        </div>
        <div class="fee-line total">
          <span>合计</span>
          <span class="price"
            ><span class="symbol">￥</span>{{ fenToYuan(totalAmount).toFixed(2) }}</span
          >
        </div>
        <p v-if="selectedCoupon" class="tip">已选优惠券；下单后显示服务端计算的优惠后金额。</p>
      </section>

      <!-- 备注 / 餐具 / 发票 -->
      <section class="card">
        <div class="row-link" @click="goRemark">
          <span>备注</span>
          <span class="row-value ellipsis">{{ remark || '推荐使用无接触配送' }}</span>
          <img src="/static/toRight.png" alt="" />
        </div>

        <div class="row-block">
          <div class="row-head">
            <span>餐具数量</span>
            <span class="row-value">{{ tablewareOption }}</span>
          </div>
          <el-radio-group v-model="tablewareOption" class="tableware-group">
            <el-radio v-for="opt in TABLEWARE_OPTIONS" :key="opt" :value="opt">
              {{ opt }}
            </el-radio>
          </el-radio-group>
          <p class="tip">
            按政府条例要求：商家不得主动向您提供一次性餐具，请按需选择餐具数量
          </p>
        </div>

        <div class="row-link static">
          <span>发票</span>
          <span class="row-value">请联系商家提供</span>
        </div>
      </section>
    </div>

    <!-- 底部结算栏 -->
    <footer class="order-bar">
      <div class="bar-count">
        <img src="/static/btn_waiter_sel.png" alt="" />
        <span>{{ cart.totalCount }}</span>
      </div>
      <div class="bar-total">
        <span class="symbol">￥</span>{{ fenToYuan(totalAmount).toFixed(2) }}
      </div>
      <button class="bar-submit" type="button" :disabled="submitting" @click="submit">
        去支付
      </button>
    </footer>
  </div>
</template>

<style scoped>
.order-page {
  min-height: 100vh;
  padding-bottom: 64px;
  background: var(--sky-bg);
}

.order-body {
  padding: 10px;
}

.card {
  position: relative;
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

/* 地址 */
.address-card {
  cursor: pointer;
}

.addr-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding-right: 24px;
}

.addr-tag {
  flex: 0 0 auto;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--sky-accent);
  font-size: 11px;
}

.addr-text {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.addr-name {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.addr-empty {
  padding: 6px 0;
  color: var(--sky-text-weak);
  font-size: 15px;
}

.addr-arrow {
  position: absolute;
  top: 50%;
  right: 12px;
  width: 14px;
  height: 14px;
  transform: translateY(-50%);
}

/* 明细 */
.dish-line {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--sky-border);
}

.dish-line:last-child {
  border-bottom: 0;
}

.dish-line-img {
  flex: 0 0 50px;
  width: 50px;
  height: 50px;
  border-radius: 6px;
  object-fit: cover;
  background: #f2f2f2;
}

.dish-line-main {
  flex: 1;
  min-width: 0;
}

.dish-line-name {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.dish-line-flavor {
  margin: 2px 0 0;
  color: var(--sky-text-weak);
  font-size: 12px;
}

.dish-line-price {
  margin: 4px 0 0;
  color: var(--sky-price);
  font-size: 15px;
  font-weight: 600;
}

.dish-line-price .symbol {
  font-size: 11px;
}

.dish-line-count {
  margin-left: 8px;
  color: var(--sky-text-weak);
  font-size: 12px;
  font-weight: 400;
}

/* 费用 */
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

/* 行链接 */
.row-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px solid var(--sky-border);
  font-size: 14px;
  cursor: pointer;
}

.row-link.static {
  border-bottom: 0;
  cursor: default;
}

.row-link img {
  width: 14px;
  height: 14px;
}

.row-value {
  flex: 1;
  color: var(--sky-text-weak);
  font-size: 13px;
  text-align: right;
}

.row-block {
  padding: 10px 0;
  border-bottom: 1px solid var(--sky-border);
}

.row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}

.tableware-group {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 0;
  margin-top: 6px;
}

.tip {
  margin: 6px 0 0;
  color: var(--sky-text-weak);
  font-size: 11px;
  line-height: 1.6;
}

.full {
  width: 100%;
}

/* 底部栏 */
.order-bar {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  align-items: center;
  width: 100%;
  max-width: 1000px;
  height: 56px;
  padding: 0 12px;
  background: #3c3c3c;
  transform: translateX(-50%);
}

.bar-count {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #fff;
  font-size: 15px;
}

.bar-count img {
  width: 40px;
  height: 40px;
}

.bar-total {
  flex: 1;
  margin-left: 10px;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

.bar-total .symbol {
  font-size: 12px;
}

.bar-submit {
  height: 36px;
  padding: 0 22px;
  border: 0;
  border-radius: 18px;
  background: var(--sky-accent);
  color: var(--sky-text);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}

.bar-submit:disabled {
  background: #6b6b6b;
  color: #d0d0d0;
  cursor: not-allowed;
}
</style>
