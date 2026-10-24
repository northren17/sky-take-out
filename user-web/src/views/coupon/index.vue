<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppNavBar from '@/components/AppNavBar.vue'
import { SUCCESS_CODE } from '@/constants/business'
import { ElMessage } from 'element-plus'
import { ArrowRight, Ticket } from '@element-plus/icons-vue'
import {
  claimCoupon,
  getCouponActivities,
  getCouponClaimStatus,
  getMyCoupons,
  redeemCoupon,
  type CouponActivity,
  type CouponClaimStatus,
  type UserCoupon,
} from '@/api/modules/coupon'

const router = useRouter()
const activities = ref<CouponActivity[]>([])
const coupons = ref<UserCoupon[]>([])
const page = ref(1)
const total = ref(0)
const activeFilter = ref<'all' | 'available' | 'used' | 'expired'>('all')
const couponLoading = ref(false)
const activityLoading = ref(false)
const claiming = ref<number | null>(null)
const pendingClaims = ref<Record<number, string>>({})
const redeemCode = ref('')

const statusFilters = [
  { label: '全部', value: 'all' as const, status: undefined },
  { label: '可使用', value: 'available' as const, status: 0 },
  { label: '已使用', value: 'used' as const, status: 2 },
  { label: '已过期', value: 'expired' as const, status: 3 },
]
const hasMore = computed(() => coupons.value.length < total.value)
const selectedStatus = computed(
  () => statusFilters.find((filter) => filter.value === activeFilter.value)?.status,
)

function createRequestId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}

function toTime(value: string) {
  return Date.parse(value.replace(' ', 'T'))
}

function activityState(item: CouponActivity) {
  const now = Date.now()
  if (Number(item.activityStatus) === 2) return '已暂停'
  if (toTime(item.startTime) > now) return '未开始'
  if (toTime(item.endTime) <= now || item.stockRemaining <= 0) return '已结束'
  return '进行中'
}

function activityCanClaim(item: CouponActivity) {
  return activityState(item) === '进行中' && item.claimedByCurrentUser < item.perUserLimit
}

function claimLabel(item: CouponActivity) {
  if (pendingClaims.value[item.id]) return '领取处理中'
  if (item.claimedByCurrentUser >= item.perUserLimit) return '已达限领'
  if (activityState(item) === '未开始') return '未开始'
  if (activityState(item) === '已暂停') return '已暂停'
  if (activityState(item) === '已结束') return '已结束'
  return '立即领取'
}

async function refreshAfterClaim() {
  await Promise.all([loadActivities(), loadMine(true)])
}

async function pollClaimStatus(activityId: number, requestId: string) {
  let notifiedOfNetworkFailure = false
  while (pendingClaims.value[activityId] === requestId) {
    await new Promise((resolve) => window.setTimeout(resolve, 1200))
    try {
      const response = await getCouponClaimStatus(requestId)
      if (response.code !== SUCCESS_CODE) throw new Error('领取状态接口返回失败')
      const claim = response.data
      if (claim.status === 0) continue
      delete pendingClaims.value[activityId]
      if (claim.status === 1) {
        ElMessage.success('优惠券领取成功')
        await refreshAfterClaim()
      } else {
        ElMessage.error(claim.failureReason || '优惠券领取失败')
        await loadActivities()
      }
      return
    } catch (error) {
      console.error('查询优惠券领取状态失败', error)
      if (!notifiedOfNetworkFailure) {
        ElMessage.warning('领取结果查询暂时失败，正在重试')
        notifiedOfNetworkFailure = true
      }
      await new Promise((resolve) => window.setTimeout(resolve, 3000))
    }
  }
}

function offerText(type: number, amount: number, rate: number) {
  if (type === 2) return `${(rate * 10).toFixed(1)}折`
  return `¥${Number(amount).toFixed(0)}`
}

function activityOfferText(type: number, threshold: number, amount: number, rate: number) {
  if (type === 2) return `满 ¥${Number(threshold).toFixed(0)} 打 ${(rate * 10).toFixed(1)} 折`
  if (type === 3) return `无门槛减 ¥${Number(amount).toFixed(0)}`
  return `满 ¥${Number(threshold).toFixed(0)} 减 ¥${Number(amount).toFixed(0)}`
}

function thresholdText(type: number, threshold: number) {
  if (type === 3 || threshold <= 0) return '无门槛可用'
  return `满 ¥${Number(threshold).toFixed(0)} 可用`
}

function couponScopeText(scopeType: number) {
  if (scopeType === 2) return '指定菜品可用'
  if (scopeType === 3) return '指定分类可用'
  return '全场可用'
}

function couponStatusText(status: number) {
  return ['可使用', '使用中', '已使用', '已过期'][status] ?? '状态未知'
}

function formatTime(value?: string) {
  if (!value) return ''
  const normalized = value.replace(' ', 'T')
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

function useCoupon() {
  void router.push({ path: '/index', hash: '#menu' })
}

function selectFilter(value: (typeof statusFilters)[number]['value']) {
  activeFilter.value = value
  void loadMine(true)
}

async function loadActivities() {
  activityLoading.value = true
  try {
    const response = await getCouponActivities()
    if (response.code === SUCCESS_CODE) activities.value = response.data ?? []
  } finally {
    activityLoading.value = false
  }
}

async function loadMine(reset = false) {
  if (couponLoading.value && !reset) return
  couponLoading.value = true
  if (reset) {
    page.value = 1
    coupons.value = []
  }
  try {
    const response = await getMyCoupons(page.value, 10, selectedStatus.value)
    if (response.code === SUCCESS_CODE && response.data) {
      coupons.value = reset ? response.data.records ?? [] : [...coupons.value, ...(response.data.records ?? [])]
      total.value = Number(response.data.total ?? 0)
    }
  } finally {
    couponLoading.value = false
  }
}

async function doClaim(item: CouponActivity) {
  if (!activityCanClaim(item) || claiming.value !== null) return
  claiming.value = item.id
  try {
    const response = await claimCoupon(item.id, createRequestId())
    if (response.code !== SUCCESS_CODE || !response.data) {
      ElMessage.error('领取请求未能受理')
      return
    }
    const claim: CouponClaimStatus = response.data
    if (claim.status === 1) {
      ElMessage.success('优惠券领取成功')
      await refreshAfterClaim()
    } else if (claim.status === 2) {
      ElMessage.error(claim.failureReason || '优惠券领取失败')
      await loadActivities()
    } else {
      pendingClaims.value[item.id] = claim.businessNo
      ElMessage.info('领取请求已受理，正在处理')
      void pollClaimStatus(item.id, claim.businessNo)
    }
  } finally {
    claiming.value = null
  }
}

async function doRedeem() {
  if (!redeemCode.value.trim() || claiming.value !== null || pendingClaims.value[-1]) return
  claiming.value = -1
  try {
    const response = await redeemCoupon(redeemCode.value.trim())
    if (response.code !== SUCCESS_CODE || !response.data) {
      ElMessage.error('兑换请求未能受理')
      return
    }
    redeemCode.value = ''
    const claim = response.data
    if (claim.status === 1) {
      ElMessage.success('兑换成功，优惠券已到账')
      await refreshAfterClaim()
    } else if (claim.status === 2) {
      ElMessage.error(claim.failureReason || '兑换失败')
    } else {
      pendingClaims.value[-1] = claim.businessNo
      ElMessage.info('兑换请求已受理，正在处理')
      void pollClaimStatus(-1, claim.businessNo)
    }
  } finally {
    claiming.value = null
  }
}

function loadMore() {
  if (!hasMore.value || couponLoading.value) return
  page.value += 1
  void loadMine()
}

onMounted(() => {
  void Promise.all([loadActivities(), loadMine(true)])
})
onUnmounted(() => {
  pendingClaims.value = {}
})
</script>

<template>
  <div class="coupon-page">
    <AppNavBar title="优惠券" />
    <main class="coupon-content">
      <section class="redeem-panel" aria-labelledby="redeem-title">
        <div class="redeem-copy">
          <span class="section-eyebrow">A LITTLE TREAT FOR YOU</span>
          <h1 id="redeem-title">兑换专属优惠</h1>
          <p>输入兑换码领取专属券</p>
        </div>
        <form class="redeem-form" @submit.prevent="doRedeem">
          <el-input
            v-model="redeemCode"
            maxlength="32"
            clearable
            aria-label="优惠券兑换码"
            placeholder="请输入兑换码"
            :disabled="Boolean(pendingClaims[-1])"
          >
            <template #prefix><el-icon><Ticket /></el-icon></template>
          </el-input>
          <el-button
            type="primary"
            native-type="submit"
            :loading="claiming === -1"
            :disabled="Boolean(pendingClaims[-1])"
          >
            {{ pendingClaims[-1] ? '兑换处理中' : '兑换优惠券' }}
            <el-icon v-if="!pendingClaims[-1]"><ArrowRight /></el-icon>
          </el-button>
        </form>
      </section>

      <section class="owned-section" aria-labelledby="owned-title">
        <div class="section-heading">
          <div>
            <span class="section-eyebrow">YOUR SAVINGS</span>
            <h2 id="owned-title">我的优惠券</h2>
          </div>
          <span class="coupon-total">共 {{ total }} 张</span>
        </div>

        <div class="filter-tabs" role="tablist" aria-label="优惠券状态筛选">
          <button
            v-for="filter in statusFilters"
            :key="filter.value"
            type="button"
            role="tab"
            :aria-selected="activeFilter === filter.value"
            :class="{ active: activeFilter === filter.value }"
            @click="selectFilter(filter.value)"
          >
            {{ filter.label }}
          </button>
        </div>

        <div v-loading="couponLoading" class="coupon-list">
          <div v-if="coupons.length" class="coupon-grid">
            <article
              v-for="item in coupons"
              :key="item.id"
              class="coupon-card"
              :class="{
                'is-used': item.status === 2,
                'is-expired': item.status === 3,
                'is-locked': item.status === 1,
                'is-discount': item.couponTypeSnapshot === 2,
              }"
            >
              <div class="coupon-value">
                <strong>
                  <template v-if="item.couponTypeSnapshot === 2">
                    {{ offerText(item.couponTypeSnapshot, item.discountAmountSnapshot, item.discountRateSnapshot) }}
                  </template>
                  <template v-else>
                    <small>¥</small>{{ Number(item.discountAmountSnapshot).toFixed(0) }}
                  </template>
                </strong>
                <span>{{ thresholdText(item.couponTypeSnapshot, item.thresholdAmountSnapshot) }}</span>
              </div>
              <div class="coupon-details">
                <div class="coupon-title-row">
                  <h3>{{ item.couponNameSnapshot }}</h3>
                  <span class="scope-tag">{{ couponScopeText(item.scopeTypeSnapshot) }}</span>
                </div>
                <p class="coupon-validity">
                  有效期：{{ formatTime(item.validStartTime) }} 至 {{ formatTime(item.validEndTime) }}
                </p>
                <p v-if="item.couponTypeSnapshot === 2 && item.maxDiscountAmountSnapshot > 0" class="coupon-limit">
                  最高优惠 ¥{{ Number(item.maxDiscountAmountSnapshot).toFixed(0) }}
                </p>
                <div class="coupon-card-footer">
                  <span class="coupon-status" :class="`status-${item.status}`">
                    {{ couponStatusText(item.status) }}
                  </span>
                  <el-button
                    v-if="item.status === 0"
                    class="use-coupon-button"
                    type="primary"
                    @click="useCoupon"
                  >
                    去使用
                    <el-icon><ArrowRight /></el-icon>
                  </el-button>
                  <span v-else class="status-stamp" :class="`stamp-${item.status}`">
                    {{ couponStatusText(item.status) }}
                  </span>
                </div>
              </div>
            </article>
          </div>

          <div v-else-if="!couponLoading" class="coupon-empty">
            <span class="empty-icon"><el-icon><Ticket /></el-icon></span>
            <strong>暂无优惠券</strong>
            <p>暂无优惠券，快去兑换领取</p>
          </div>

          <el-button
            v-if="hasMore && coupons.length"
            class="load-more"
            :loading="couponLoading"
            @click="loadMore"
          >
            加载更多
          </el-button>
        </div>
      </section>

      <section class="activities-section" aria-labelledby="activities-title">
        <div class="section-heading">
          <div>
            <span class="section-eyebrow">MORE TO DISCOVER</span>
            <h2 id="activities-title">领券活动</h2>
          </div>
          <span class="section-caption">领取更多专属优惠</span>
        </div>
        <div v-loading="activityLoading" class="activity-grid">
          <article v-for="item in activities" :key="item.id" class="activity-card">
            <div class="activity-offer">{{ activityOfferText(item.couponType, item.thresholdAmount, item.discountAmount, item.discountRate) }}</div>
            <div class="activity-info">
              <h3>{{ item.name }}</h3>
              <p>{{ item.scopeType === 1 ? '全场可用' : item.scopeType === 2 ? '指定菜品可用' : '指定分类可用' }}</p>
              <small>剩余 {{ item.stockRemaining }} 张 · 每人限领 {{ item.perUserLimit }} 张</small>
              <small>
                {{ formatTime(item.startTime) }} 至 {{ formatTime(item.endTime) }}
                <template v-if="item.validityType === 1"> · 领取后 {{ item.validityDays }} 天有效</template>
              </small>
              <small v-if="item.validityType !== 1">
                券有效期：{{ formatTime(item.validStartTime) }} 至 {{ formatTime(item.validEndTime) }}
              </small>
            </div>
            <el-button
              class="claim-button"
              type="primary"
              :disabled="!activityCanClaim(item) || Boolean(pendingClaims[item.id])"
              :loading="claiming === item.id"
              @click="doClaim(item)"
            >
              {{ claimLabel(item) }}
            </el-button>
          </article>
          <div v-if="!activityLoading && !activities.length" class="activity-empty">
            暂无进行中的领券活动
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.coupon-page {
  min-height: 100vh;
  padding: 0 28px 48px;
  background:
    radial-gradient(ellipse at 92% 4%, rgb(222 238 226 / 52%), transparent 26%),
    #f5f7f4;
}

.coupon-content {
  width: min(100%, 1180px);
  margin: 0 auto;
}

.redeem-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 36px;
  min-height: 184px;
  padding: 32px 42px;
  overflow: hidden;
  border: 1px solid #dbe9dc;
  border-radius: 20px;
  background:
    radial-gradient(circle at 91% 20%, rgb(255 255 255 / 73%), transparent 24%),
    linear-gradient(112deg, #edf6ed 0%, #e3f0e4 58%, #dcecdf 100%);
  box-shadow: 0 10px 28px rgb(39 84 54 / 6%);
}

.redeem-copy {
  min-width: 0;
}

.section-eyebrow {
  display: block;
  color: #748f79;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.19em;
}

.redeem-copy h1 {
  margin-top: 9px;
  color: #244e37;
  font-size: 25px;
  font-weight: 720;
  letter-spacing: -0.035em;
}

.redeem-copy p {
  max-width: none;
  margin-top: 7px;
  color: #718276;
  font-size: 13px;
}

.redeem-form {
  display: flex;
  flex: 0 1 500px;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.redeem-form .el-input {
  min-width: 0;
}

.redeem-form :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 0 14px;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 0 0 1px #dbe6dc inset;
}

.redeem-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #176b50 inset, 0 0 0 3px rgb(23 107 80 / 9%);
}

.redeem-form :deep(.el-input__prefix-inner > .el-icon) {
  color: #819888;
}

.redeem-form > .el-button {
  flex: 0 0 auto;
  min-height: 46px;
  padding: 0 19px;
  border: 0;
  border-radius: 9px;
  background: #176b50;
  font-size: 13px;
  font-weight: 650;
  box-shadow: 0 6px 14px rgb(23 107 80 / 14%);
}

.redeem-form > .el-button:hover {
  background: #10563f;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 16px;
}

.section-heading h2 {
  margin-top: 6px;
  color: #26382e;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.025em;
}

.coupon-total,
.section-caption {
  padding-bottom: 3px;
  color: #88948b;
  font-size: 12px;
}

.owned-section,
.activities-section {
  margin-top: 40px;
}

.filter-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 0 17px;
  border-bottom: 1px solid #e5eae5;
}

.filter-tabs button {
  position: relative;
  min-width: 74px;
  padding: 0 12px 13px;
  color: #78847b;
  font-size: 13px;
  font-weight: 550;
  white-space: nowrap;
  transition: color 150ms ease;
}

.filter-tabs button:hover,
.filter-tabs button.active {
  color: #176b50;
}

.filter-tabs button.active {
  font-weight: 700;
}

.filter-tabs button.active::after {
  position: absolute;
  right: 12px;
  bottom: -1px;
  left: 12px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: #176b50;
  content: '';
}

.coupon-list {
  min-height: 160px;
}

.coupon-grid,
.activity-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.coupon-card {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 166px;
  overflow: hidden;
  border: 1px solid #dfe9df;
  border-radius: 14px;
  background:
    radial-gradient(circle at 0 50%, transparent 8px, #fff 8.5px) left center / 14px 22px repeat-y,
    #fff;
  box-shadow: 0 5px 17px rgb(36 74 46 / 5%);
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.coupon-card:not(.is-used):not(.is-expired):hover {
  transform: translateY(-3px);
  box-shadow: 0 13px 28px rgb(36 74 46 / 11%);
}

.coupon-value {
  position: relative;
  display: flex;
  flex: 0 0 31%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 118px;
  padding: 18px 12px;
  border-right: 1px dashed #bfd6c4;
  background: linear-gradient(145deg, #f0f7ef, #e7f2e7);
  color: #176b50;
  text-align: center;
}

.coupon-value::before,
.coupon-value::after {
  position: absolute;
  right: -8px;
  z-index: 1;
  width: 15px;
  height: 15px;
  border: 1px solid #dfe9df;
  border-radius: 50%;
  background: #f5f7f4;
  content: '';
}

.coupon-value::before {
  top: -8px;
}

.coupon-value::after {
  bottom: -8px;
}

.coupon-value strong {
  color: #176b50;
  font-size: clamp(25px, 2.3vw, 36px);
  font-weight: 760;
  letter-spacing: -0.05em;
  line-height: 1;
  white-space: nowrap;
}

.coupon-value strong small {
  margin-right: 2px;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0;
}

.coupon-value > span {
  color: #718b76;
  font-size: 11px;
  white-space: nowrap;
}

.coupon-details {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 9px;
  min-width: 0;
  padding: 19px 20px 17px 23px;
}

.coupon-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.coupon-title-row h3 {
  overflow: hidden;
  color: #2d3c32;
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-tag {
  flex: 0 0 auto;
  padding: 3px 7px;
  border: 1px solid #deeadf;
  border-radius: 999px;
  background: #f4f8f3;
  color: #63806a;
  font-size: 9px;
  line-height: 1.3;
}

.coupon-validity,
.coupon-limit {
  max-width: none;
  color: #879188;
  font-size: 10px;
  line-height: 1.5;
}

.coupon-limit {
  color: #738478;
}

.coupon-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 30px;
  margin-top: auto;
}

.coupon-status {
  color: #548060;
  font-size: 10px;
  font-weight: 600;
}

.use-coupon-button {
  min-height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 7px;
  background: #176b50;
  font-size: 11px;
}

.use-coupon-button:hover {
  background: #10563f;
}

.status-stamp {
  display: grid;
  min-width: 54px;
  min-height: 26px;
  place-items: center;
  border: 1px solid currentColor;
  border-radius: 5px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  transform: rotate(-7deg);
}

.stamp-1,
.stamp-2 {
  color: #a7ada8;
}

.stamp-3 {
  color: #be8b59;
}

.is-used,
.is-expired,
.is-locked {
  border-color: #e5e8e5;
  background:
    radial-gradient(circle at 0 50%, transparent 8px, #f6f7f6 8.5px) left center / 14px 22px repeat-y,
    #f6f7f6;
  box-shadow: none;
}

.is-used .coupon-value,
.is-expired .coupon-value,
.is-locked .coupon-value {
  border-right-color: #d4d9d4;
  background: linear-gradient(145deg, #f0f1f0, #e9ece9);
  color: #929a93;
}

.is-used .coupon-value strong,
.is-expired .coupon-value strong,
.is-locked .coupon-value strong {
  color: #929a93;
}

.is-used .coupon-value > span,
.is-expired .coupon-value > span,
.is-locked .coupon-value > span,
.is-used .coupon-title-row h3,
.is-expired .coupon-title-row h3,
.is-locked .coupon-title-row h3 {
  color: #9ca39d;
}

.is-expired .status-3 {
  color: #b47742;
}

.is-used::after {
  position: absolute;
  top: 50%;
  right: 14%;
  color: rgb(125 135 127 / 9%);
  content: '已使用';
  font-size: 44px;
  font-weight: 800;
  letter-spacing: 0.12em;
  pointer-events: none;
  transform: translateY(-50%) rotate(-15deg);
}

.is-used .coupon-card-footer,
.is-used .coupon-title-row,
.is-used .coupon-validity,
.is-used .coupon-limit {
  position: relative;
  z-index: 1;
}

.coupon-empty,
.activity-empty {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #89958c;
  text-align: center;
}

.empty-icon {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border-radius: 18px;
  background: #eaf3e9;
  color: #4d8260;
  font-size: 27px;
}

.coupon-empty strong {
  margin-top: 14px;
  color: #46574b;
  font-size: 14px;
}

.coupon-empty p {
  max-width: none;
  margin-top: 5px;
  font-size: 12px;
}

.load-more {
  display: flex;
  margin: 22px auto 0;
  border-color: #d9e6da;
  color: #176b50;
}

.activities-section {
  padding-top: 27px;
  border-top: 1px solid #e5eae5;
}

.activity-grid {
  margin-top: 18px;
}

.activity-card {
  display: flex;
  align-items: center;
  gap: 18px;
  min-width: 0;
  min-height: 138px;
  padding: 20px;
  border: 1px solid #e0e9e1;
  border-radius: 13px;
  background: #fff;
  box-shadow: 0 4px 15px rgb(36 74 46 / 4%);
}

.activity-offer {
  display: grid;
  flex: 0 0 112px;
  min-height: 84px;
  place-items: center;
  border-radius: 10px;
  background: #edf6ed;
  color: #176b50;
  font-size: 19px;
  font-weight: 750;
  white-space: nowrap;
}

.activity-info {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 5px;
}

.activity-info h3 {
  overflow: hidden;
  color: #34453a;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-info p,
.activity-info small {
  max-width: none;
  overflow: hidden;
  color: #89948b;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-info p {
  color: #61806a;
}

.claim-button {
  flex: 0 0 auto;
  min-height: 34px;
  padding: 0 13px;
  border: 0;
  border-radius: 7px;
  background: #176b50;
  font-size: 11px;
}

.claim-button:hover {
  background: #10563f;
}

.section-caption {
  padding-bottom: 2px;
}

@media (max-width: 1000px) {
  .coupon-page {
    padding-inline: 20px;
  }

  .redeem-panel {
    gap: 22px;
    padding-inline: 30px;
  }

  .coupon-grid,
  .activity-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 13px;
  }

  .coupon-value {
    flex-basis: 29%;
    min-width: 100px;
  }

  .coupon-details {
    gap: 7px;
    padding: 16px 14px 14px 18px;
  }

  .activity-card {
    gap: 12px;
    padding: 16px;
  }

  .activity-offer {
    flex-basis: 94px;
    font-size: 16px;
  }
}

@media (max-width: 700px) {
  .coupon-page {
    padding: 0 14px 36px;
  }

  .redeem-panel {
    align-items: stretch;
    flex-direction: column;
    gap: 18px;
    min-height: 0;
    padding: 23px 20px;
    border-radius: 15px;
  }

  .redeem-copy h1 {
    font-size: 22px;
  }

  .redeem-form {
    flex-basis: auto;
    gap: 8px;
  }

  .redeem-form > .el-button {
    padding-inline: 12px;
    font-size: 11px;
  }

  .owned-section,
  .activities-section {
    margin-top: 30px;
  }

  .section-heading h2 {
    font-size: 20px;
  }

  .filter-tabs {
    gap: 0;
    margin-top: 17px;
  }

  .filter-tabs button {
    flex: 1;
    min-width: 0;
    padding-inline: 6px;
    font-size: 12px;
  }

  .coupon-grid,
  .activity-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }

  .coupon-card {
    min-height: 148px;
  }

  .coupon-value {
    flex-basis: 32%;
    min-width: 108px;
  }

  .coupon-value strong {
    font-size: 32px;
  }

  .coupon-details {
    padding: 15px 16px 13px 20px;
  }

  .activity-card {
    min-height: 126px;
  }

  .activity-offer {
    flex-basis: 96px;
  }
}

@media (max-width: 400px) {
  .coupon-page {
    padding-inline: 10px;
  }

  .redeem-panel {
    padding-inline: 16px;
  }

  .redeem-form > .el-button {
    padding-inline: 9px;
  }

  .coupon-card {
    min-height: 138px;
  }

  .coupon-value {
    flex-basis: 29%;
    min-width: 92px;
    padding-inline: 8px;
  }

  .coupon-value strong {
    font-size: 27px;
  }

  .coupon-details {
    gap: 6px;
    padding-right: 11px;
    padding-left: 15px;
  }

  .coupon-title-row h3 {
    font-size: 13px;
  }

  .scope-tag {
    padding-inline: 5px;
    font-size: 8px;
  }

  .activity-card {
    gap: 10px;
    padding: 13px;
  }

  .activity-offer {
    flex-basis: 84px;
    min-height: 76px;
    font-size: 14px;
  }

  .claim-button {
    padding-inline: 9px;
    font-size: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .coupon-card,
  .filter-tabs button {
    transition: none;
  }
}
</style>
