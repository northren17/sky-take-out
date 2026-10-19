<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import { cancelOrder, paymentOrder } from '@/api/modules/order'
import { SUCCESS_CODE, PAY_METHOD } from '@/constants/business'
import { fenToYuan } from '@/stores/cart'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 支付订单（对应小程序 `pages/pay/index`）
 *
 * 与源码一致：
 *  - 倒计时 15 分钟，基于下单时间 `orderTime` 计算（`m15 = 15 * 60 * 1000`）。
 *  - 超时后 `timeout = true`，点击按钮改为「取消订单」再跳订单详情。
 *  - 支付方式单选，`payMethod: activeRadio === 0 ? 1 : 2`。
 *  - 支付成功跳成功页；失败 toast 使用后端 `msg`。
 *
 * 修正的源码缺陷：
 *  - 源码倒计时基于 `orderDataInfo.orderTime`，若用户刷新页面 Vuex 即清空 → 白屏；
 *    本工程把下单返回落到 store（并可从路由参数兜底），刷新不丢。
 *  - 源码 `@change="styleChange"` 绑定的方法不存在，这里改为 `v-model`。
 */
const route = useRoute()
const router = useRouter()
const checkout = useCheckoutStore()

const orderId = computed(() => String(route.params.orderId ?? ''))
const order = computed(() => checkout.orderData)

const timeout = ref(false)
const remain = ref('15:00')
const payMethodIndex = ref(0)
const payMethodList = ['微信支付']
const paying = ref(false)

let timer: number | undefined

const amountText = computed(() => fenToYuan(order.value?.orderAmount).toFixed(2))

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function tick() {
  const orderTime = order.value?.orderTime
  if (!orderTime) {
    // 没有下单时间（例如直接访问本页）时不做倒计时，交由用户手动操作
    stopTimer()
    remain.value = '--:--'
    return
  }
  const end = Date.parse(orderTime.replace(/-/g, '/'))
  const msec = 15 * 60 * 1000 - (Date.now() - end)
  if (msec <= 0) {
    timeout.value = true
    remain.value = '00:00'
    stopTimer()
    return
  }
  const min = String(Math.floor((msec / 1000 / 60) % 60)).padStart(2, '0')
  const sec = String(Math.floor((msec / 1000) % 60)).padStart(2, '0')
  remain.value = `${min}:${sec}`
}

async function handlePay() {
  if (timeout.value) {
    // 超时：取消订单并去详情页（对齐源码）
    try {
      await cancelOrder(orderId.value)
    } catch {
      // 取消失败也允许继续查看
    }
    await router.replace(`/order/detail/${orderId.value}`)
    return
  }

  const orderNumber = order.value?.orderNumber
  if (!orderNumber) {
    ElMessage.warning('缺少订单号，无法支付')
    return
  }

  paying.value = true
  try {
    const res = await paymentOrder({
      orderNumber,
      payMethod: payMethodIndex.value === 0 ? PAY_METHOD.WECHAT : PAY_METHOD.ALIPAY,
    })
    if (res.code === SUCCESS_CODE) {
      stopTimer()
      await router.replace(`/success/${orderId.value}`)
      return
    }
    ElMessage.error(res.msg || '支付失败')
  } catch {
    // 请求层已提示
  } finally {
    paying.value = false
  }
}

onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
})

onUnmounted(stopTimer)
</script>

<template>
  <div class="pay-page">
    <AppNavBar title="支付订单" @back="router.replace('/index')" />

    <section class="pay-hero">
      <p v-if="timeout" class="pay-timeout">订单已超时</p>
      <p v-else class="pay-countdown">
        支付剩余时间 <span class="pay-remain">{{ remain }}</span>
      </p>
      <p class="pay-money">
        <span class="symbol">￥</span>{{ amountText }}
      </p>
      <p class="pay-order-no">苍穹餐厅-{{ order?.orderNumber ?? '—' }}</p>
    </section>

    <section class="pay-methods">
      <p class="pay-section-title">支付方式</p>
      <el-radio-group v-model="payMethodIndex" class="pay-radio-group">
        <el-radio v-for="(m, i) in payMethodList" :key="m" :value="i" class="pay-radio">
          <img class="pay-wechat" src="/static/wechat.png" alt="" />
          {{ m }}
        </el-radio>
      </el-radio-group>
    </section>

    <div class="pay-footer">
      <el-button type="warning" class="pay-submit" :loading="paying" @click="handlePay">
        {{ timeout ? '取消订单' : '确认支付' }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.pay-page {
  min-height: 100vh;
  padding-bottom: 72px;
  background: var(--sky-bg);
}

.pay-hero {
  padding: 26px 16px;
  margin: 10px;
  background: #fff;
  border-radius: 8px;
  text-align: center;
}

.pay-countdown,
.pay-timeout {
  margin: 0;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.pay-timeout {
  color: var(--sky-primary);
  font-weight: 600;
}

.pay-remain {
  color: var(--sky-primary);
  font-weight: 600;
}

.pay-money {
  margin: 10px 0 6px;
  color: var(--sky-price);
  font-size: 30px;
  font-weight: 700;
}

.pay-money .symbol {
  font-size: 16px;
}

.pay-order-no {
  margin: 0;
  color: var(--sky-text-weak);
  font-size: 12px;
}

.pay-methods {
  padding: 12px;
  margin: 0 10px;
  background: #fff;
  border-radius: 8px;
}

.pay-section-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
}

.pay-radio-group {
  display: flex;
  flex-direction: column;
}

.pay-radio {
  height: 40px;
}

.pay-wechat {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -3px;
}

.pay-footer {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  width: 100%;
  max-width: 1000px;
  padding: 10px 14px 14px;
  background: #fff;
  transform: translateX(-50%);
}

.pay-submit {
  width: 100%;
}
</style>
