<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppNavBar from '@/components/AppNavBar.vue'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 提交成功（对应小程序 `pages/success/index`）
 * 源码展示「预计 {{arrivalTime}} 送达」+ 两个按钮：返回首页 / 查看订单。
 * 其中「返回首页」带 `status=不验证` 参数（源码用来跳过首页的登录校验分支），
 * 网页端有自己的登录守卫，故直接回首页即可。
 */
const route = useRoute()
const router = useRouter()
const checkout = useCheckoutStore()

const orderId = computed(() => String(route.params.orderId ?? ''))
const arrivalTime = computed(() => checkout.arrivalTime || '一小时')
</script>

<template>
  <div class="success-page">
    <AppNavBar title="提交成功" :show-back="false" />

    <div class="success-body">
      <img class="success-icon" src="/static/success.png" alt="" />
      <h2 class="success-title">下单成功</h2>
      <p class="success-word">
        预计 <span class="success-time">{{ arrivalTime }}</span> 送达
      </p>
      <p class="success-desc">后厨已开始疯狂备餐中, 请耐心等待~</p>

      <div class="success-btns">
        <button class="btn ghost" type="button" @click="router.replace('/index')">返回首页</button>
        <button class="btn primary" type="button" @click="router.replace(`/order/detail/${orderId}`)">
          查看订单
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.success-page {
  min-height: 100vh;
  background: #fff;
}

.success-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: min(100%, 720px);
  min-width: 0;
  margin-inline: auto;
  padding: 40px 20px;
  text-align: center;
}

.success-icon {
  width: 96px;
  height: 96px;
  object-fit: contain;
}

.success-title {
  margin: 16px 0 0;
  font-size: 22px;
  font-weight: 600;
}

.success-word {
  width: 100%;
  max-width: 62ch;
  margin: 10px 0 0;
  color: var(--sky-text-sub);
  font-size: 14px;
  text-align: center;
}

.success-time {
  color: var(--sky-primary);
  font-weight: 600;
}

.success-desc {
  width: 100%;
  max-width: 62ch;
  margin: 8px 0 0;
  color: var(--sky-text-weak);
  font-size: 12px;
  text-align: center;
}

.success-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
  width: 100%;
  margin-top: 30px;
}

.btn {
  height: 38px;
  padding: 0 24px;
  border: 0;
  border-radius: 19px;
  font-size: 14px;
  cursor: pointer;
}

.btn.ghost {
  border: 1px solid var(--sky-border);
  background: #fff;
  color: var(--sky-text);
}

.btn.primary {
  background: var(--sky-accent);
  color: var(--sky-text);
  font-weight: 600;
}
</style>
