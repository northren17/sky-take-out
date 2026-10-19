<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppNavBar from '@/components/AppNavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { SUCCESS_CODE } from '@/constants/business'
import { ElMessage } from 'element-plus'
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

const tab = ref<'activities' | 'mine'>('activities')
const activities = ref<CouponActivity[]>([])
const coupons = ref<UserCoupon[]>([])
const page = ref(1)
const total = ref(0)
const statusFilter = ref<number | undefined>()
const loading = ref(false)
const claiming = ref<number | null>(null)
const pendingClaims = ref<Record<number, string>>({})
const redeemCode = ref('')

const statusOptions = [
  { label: '全部', value: undefined },
  { label: '未使用', value: 0 },
  { label: '已锁定', value: 1 },
  { label: '已使用', value: 2 },
  { label: '已过期', value: 3 },
]
const hasMore = computed(() => coupons.value.length < total.value)

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
  await loadActivities()
  if (tab.value === 'mine') await loadMine(true)
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

function offerText(type: number, threshold: number, amount: number, rate: number) {
  if (type === 2) return `满 ${threshold} 元打 ${(rate * 10).toFixed(1)} 折`
  if (type === 3) return `无门槛减 ${amount} 元`
  return `满 ${threshold} 元减 ${amount} 元`
}

async function loadActivities() {
  loading.value = true
  try {
    const response = await getCouponActivities()
    if (response.code === SUCCESS_CODE) activities.value = response.data ?? []
  } finally {
    loading.value = false
  }
}

async function loadMine(reset = false) {
  if (loading.value && !reset) return
  loading.value = true
  if (reset) {
    page.value = 1
    coupons.value = []
  }
  try {
    const response = await getMyCoupons(page.value, 10, statusFilter.value)
    if (response.code === SUCCESS_CODE && response.data) {
      coupons.value = reset ? response.data.records ?? [] : [...coupons.value, ...(response.data.records ?? [])]
      total.value = Number(response.data.total ?? 0)
    }
  } finally {
    loading.value = false
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

function switchTab(value: 'activities' | 'mine') {
  tab.value = value
  if (value === 'mine') void loadMine(true)
  else void loadActivities()
}

function loadMore() {
  if (!hasMore.value || loading.value) return
  page.value += 1
  void loadMine()
}

onMounted(loadActivities)
onUnmounted(() => {
  pendingClaims.value = {}
})
</script>

<template>
  <div class="coupon-page">
    <AppNavBar title="优惠券" />
    <div class="coupon-tabs">
      <button :class="{ active: tab === 'activities' }" @click="switchTab('activities')">领券中心</button>
      <button :class="{ active: tab === 'mine' }" @click="switchTab('mine')">我的优惠券</button>
    </div>
    <div class="coupon-redeem">
      <el-input
        v-model="redeemCode"
        maxlength="32"
        placeholder="输入兑换码"
        :disabled="Boolean(pendingClaims[-1])"
      />
      <el-button
        type="primary"
        :loading="claiming === -1"
        :disabled="Boolean(pendingClaims[-1])"
        @click="doRedeem"
      >
        {{ pendingClaims[-1] ? '兑换处理中' : '兑换优惠券' }}
      </el-button>
    </div>

    <div v-if="tab === 'activities'" v-loading="loading" class="coupon-list">
      <article v-for="item in activities" :key="item.id" class="coupon-card">
        <div class="coupon-main">
          <strong>{{ offerText(item.couponType, item.thresholdAmount, item.discountAmount, item.discountRate) }}</strong>
          <span>{{ item.name }}</span>
          <small>剩余 {{ item.stockRemaining }} 张 · 每人限领 {{ item.perUserLimit }} 张</small>
          <small>{{ item.startTime }} 至 {{ item.endTime }}</small>
          <small v-if="item.validityType === 1">领取后 {{ item.validityDays }} 天有效</small>
          <small v-else>券有效期：{{ item.validStartTime }} 至 {{ item.validEndTime }}</small>
        </div>
        <el-button type="primary" :disabled="!activityCanClaim(item) || Boolean(pendingClaims[item.id])" :loading="claiming === item.id" @click="doClaim(item)">
          {{ claimLabel(item) }}
        </el-button>
      </article>
      <EmptyState v-if="!loading && activities.length === 0" text="暂无可领取优惠券" />
    </div>

    <div v-else v-loading="loading" class="coupon-list">
      <el-select v-model="statusFilter" class="status-filter" placeholder="全部状态" @change="loadMine(true)">
        <el-option v-for="option in statusOptions" :key="String(option.value)" :label="option.label" :value="option.value" />
      </el-select>
      <article v-for="item in coupons" :key="item.id" class="coupon-card owned">
        <div class="coupon-main">
          <strong>{{ offerText(item.couponTypeSnapshot, item.thresholdAmountSnapshot, item.discountAmountSnapshot, item.discountRateSnapshot) }}</strong>
          <span>{{ item.couponNameSnapshot }}</span>
          <small>有效期：{{ item.validStartTime }} 至 {{ item.validEndTime }}</small>
        </div>
        <span class="coupon-status">{{ ['未使用', '已锁定', '已使用', '已过期'][item.status] }}</span>
      </article>
      <EmptyState v-if="!loading && coupons.length === 0" text="暂无优惠券" />
      <el-button v-if="hasMore" class="load-more" :loading="loading" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<style scoped>
.coupon-page { min-height: 100vh; padding: 0 14px 28px; background: #f6f6f6; }
.coupon-tabs { display: flex; justify-content: center; gap: 42px; padding: 16px 0; background: #fff; }
.coupon-redeem { display: flex; gap: 10px; padding: 12px 0; }
.coupon-redeem .el-input { flex: 1; }
.coupon-tabs button { border: 0; background: transparent; color: #666; font-size: 15px; }
.coupon-tabs button.active { color: #d84c32; font-weight: 700; }
.coupon-list { display: grid; gap: 12px; padding-top: 14px; }
.coupon-card { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px; border-radius: 12px; background: #fff; }
.coupon-main { display: grid; gap: 6px; min-width: 0; }
.coupon-main strong { color: #d84c32; font-size: 20px; }
.coupon-main span { color: #333; font-weight: 600; }
.coupon-main small { color: #888; font-size: 12px; }
.coupon-status { flex: 0 0 auto; color: #888; }
.status-filter { margin-bottom: 2px; }
.load-more { justify-self: center; }
</style>
