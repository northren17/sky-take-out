<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { getImageUrl } from '@/api/request'
import { getOrderHistory, repetitionOrder } from '@/api/modules/order'
import { cleanShoppingCart } from '@/api/modules/cart'
import { getUserInfo } from '@/api/modules/user'
import { SUCCESS_CODE } from '@/constants/business'
import type { OrderHistoryItem } from '@/types/api'
import { fenToYuan, useCartStore } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'
import { useUserStore } from '@/stores/user'
import { getOvertime, statusWord, sumOrderDetail } from '@/utils/order'

/**
 * 个人中心（对应小程序 `pages/my/my`）
 *
 * 与源码一致：
 *  - 头像/昵称/性别来自用户信息；源码取 `baseUserInfo`（微信授权信息），
 *    普通浏览器拿不到微信资料，因此这里允许为空并给出占位。
 *  - 展示店铺电话（源码取 Vuex `shopPhone`）。
 *  - 两个入口：地址管理、历史订单。
 *  - 「最近订单」用 `GET /user/order/historyOrders`，**不传 status**，每页 10 条，触底加载。
 */
const router = useRouter()
const user = useUserStore()
const shop = useShopStore()
const cart = useCartStore()
const checkout = useCheckoutStore()

const PAGE_SIZE = 10

const list = ref<OrderHistoryItem[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)

const nickName = computed(() => {
  // 网页版账号登录：显示账号（或注册时填写的昵称）
  if (user.account) return user.baseUserInfo.nickName ?? user.account
  return user.baseUserInfo.nickName ?? '微信用户'
})
const avatarUrl = computed(() => user.baseUserInfo.avatarUrl ?? '')
const gender = computed(() => Number(user.baseUserInfo.gender ?? 0))

/**
 * 拉取当前登录用户信息，填充昵称/头像占位（失败不阻断页面）
 */
async function loadUserInfo() {
  if (!user.token) return
  try {
    const res = await getUserInfo()
    if (res.code === SUCCESS_CODE && res.data) {
      user.setBaseUserInfo({
        ...user.baseUserInfo,
        nickName: res.data.name ?? res.data.username ?? user.baseUserInfo.nickName,
      })
    }
  } catch {
    // 请求层已提示；401 时请求层会跳转登录页
  }
}

function statusText(item: OrderHistoryItem): string {
  return statusWord(Number(item.status), getOvertime(item.orderTime ?? undefined))
}

const hasMore = () => list.value.length < Number(total.value)

async function loadList(reset = false) {
  if (loading.value) return
  loading.value = true
  if (reset) {
    page.value = 1
    list.value = []
  }
  try {
    const res = await getOrderHistory({ page: page.value, pageSize: PAGE_SIZE })
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

function loadMore() {
  if (!hasMore()) return
  page.value += 1
  void loadList()
}

function goAddress() {
  checkout.setAddressBackPath('/my')
  router.push('/address')
}

function goOrder() {
  router.push('/history')
}

function goCoupons() {
  router.push('/coupons')
}

function goDiningPreferences() {
  router.push('/dining-preferences')
}

function goDetail(id?: number) {
  if (id === undefined) return
  router.push(`/order/detail/${id}`)
}

function goPay(item: OrderHistoryItem) {
  router.push(`/pay/${item.id}`)
}

async function oneOrderFun(id?: number) {
  if (id === undefined) return
  await cleanShoppingCart()
  try {
    const res = await repetitionOrder(id)
    if (res.code === SUCCESS_CODE) {
      await cart.fetchCart()
      await router.replace('/index')
    }
  } catch {
    // 请求层已提示
  }
}

function callShop() {
  window.location.href = `tel:${shop.phone}`
}

async function logout() {
  const serverRevoked = await user.logout()
  if (!serverRevoked) {
    ElMessage.warning('本地已退出，但服务端会话及语音连接撤销状态未确认')
  }
  router.replace('/login')
}

onMounted(async () => {
  await shop.fetchStatus()
  await loadUserInfo()
  await loadList(true)
})
</script>

<template>
  <div class="my-page">
    <!-- 头部 -->
    <div class="my-head">
      <div class="my-head-inner">
        <img v-if="avatarUrl" v-image-fallback class="my-avatar" :src="avatarUrl" :alt="`${nickName}的头像`" />
        <span v-else class="my-avatar my-avatar-fallback" aria-hidden="true">{{ nickName.slice(0, 1) }}</span>
        <div class="my-name-box">
          <p class="my-name">
            {{ nickName }}
            <img v-if="gender === 2" class="my-gender" src="/static/girl.png" alt="" />
            <img v-else-if="gender === 1" class="my-gender" src="/static/boy.png" alt="" />
          </p>
          <p class="my-phone" @click="callShop">{{ shop.phone }}</p>
        </div>
        <button class="my-logout" type="button" @click="logout">退出登录</button>
      </div>
    </div>

    <!-- 入口 -->
    <div class="my-entries">
      <button class="entry" type="button" @click="goAddress">
        <img src="/static/address.png" alt="" />
        <span>地址管理</span>
        <img class="entry-arrow" src="/static/toRight.png" alt="" />
      </button>
      <button class="entry" type="button" @click="goOrder">
        <img src="/static/order.png" alt="" />
        <span>历史订单</span>
        <img class="entry-arrow" src="/static/toRight.png" alt="" />
      </button>
      <button class="entry" type="button" @click="goCoupons">
        <img src="/static/order.png" alt="" />
        <span>我的优惠券</span>
        <img class="entry-arrow" src="/static/toRight.png" alt="" />
      </button>
      <button class="entry" type="button" @click="goDiningPreferences">
        <img src="/static/order.png" alt="" />
        <span>口味与每日预算</span>
        <img class="entry-arrow" src="/static/toRight.png" alt="" />
      </button>
    </div>

    <!-- 最近订单 -->
    <template v-if="list.length > 0">
      <p class="my-section-title">最近订单</p>
    </template>

    <div v-loading="loading" class="my-orders">
      <div v-for="item in list" :key="item.id" class="order-card">
        <div class="order-head">
          <span class="order-time">{{ item.orderTime }}</span>
          <span class="order-status">{{ statusText(item) }}</span>
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
            <span class="order-count">共 {{ sumOrderDetail(item.orderDetailList).count }} 件</span>
          </div>
        </div>
        <div class="order-actions">
          <button class="btn" type="button" @click="oneOrderFun(item.id)">再来一单</button>
          <button
            v-if="Number(item.status) === 1 && getOvertime(item.orderTime ?? undefined) > 0"
            class="btn warn"
            type="button"
            @click="goPay(item)"
          >
            去支付
          </button>
        </div>
      </div>

      <EmptyState
        v-if="list.length === 0 && !loading"
        text="还没有订单，去点一份吧"
        image="/static/no_order.png"
      />

      <div v-if="hasMore()" class="load-more">
        <el-button :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
      <p v-else-if="list.length > 0" class="no-more">没有更多了</p>
    </div>
  </div>
</template>

<style scoped>
.my-page {
  min-height: 100vh;
  padding-bottom: 16px;
  background: #f6f6f6;
}

/* 头部 */
.my-head {
  padding: 18px 14px;
  background: var(--sky-accent);
  color: #fff;
}

.my-head-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}

.my-avatar {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: #fff;
  object-fit: cover;
}

.my-avatar-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 58px;
  color: var(--sky-primary);
  font-size: 22px;
  font-weight: 700;
}

.my-name-box {
  flex: 1;
  min-width: 0;
}

.my-name {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.my-gender {
  width: 16px;
  height: 16px;
}

.my-phone {
  margin: 4px 0 0;
  color: rgb(255 255 255 / 82%);
  font-size: 13px;
  cursor: pointer;
}

.my-logout {
  height: 30px;
  padding: 0 12px;
  border: 1px solid rgba(0, 0, 0, 0.15);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.88);
  color: var(--sky-text);
  font-size: 12px;
  cursor: pointer;
}

/* 入口 */
.my-entries {
  margin: 10px;
  background: #fff;
  border-radius: 8px;
}

.entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 48px;
  padding: 0 12px;
  border: 0;
  border-bottom: 1px solid var(--sky-border);
  background: transparent;
  font-size: 14px;
  cursor: pointer;
}

.entry:last-child {
  border-bottom: 0;
}

.entry img {
  width: 18px;
  height: 18px;
}

.entry-arrow {
  width: 14px !important;
  height: 14px !important;
  margin-left: auto;
}

.my-section-title {
  margin: 14px 12px 6px;
  color: var(--sky-text-sub);
  font-size: 13px;
}

/* 订单 */
.my-orders {
  padding: 0 10px;
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

.order-time,
.order-status {
  color: var(--sky-text-sub);
  font-size: 13px;
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
