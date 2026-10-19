<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 订单备注（对应小程序 `pages/remark/index`）
 *
 * 源码的计数规则是「半角算 0.5、全角算 1」的加权长度（上限 50 单位），
 * 但截断用的是 `substring(0, 50)` 原始字符数，两者不一致；
 * 并且它引用的 `getRemark` computed 已被注释掉（`if (this.getRemark === '')` 是死代码）。
 * 本工程统一按**原始字符数**限制 50 字，与 `textarea` 的 `maxlength` 语义一致。
 */
const router = useRouter()
const checkout = useCheckoutStore()

const MAX_LEN = 50
const remark = ref(checkout.remark)
const PLACEHOLDER =
  '无接触配送，将商品挂家门口或放前台，地址封闭管理时请电话联系'

const count = computed(() => remark.value.length)

function save() {
  checkout.setRemark(remark.value.slice(0, MAX_LEN))
  ElMessage.success('备注已保存')
  router.replace('/order')
}

function goBack() {
  // 源码 goBack 与保存行为一致，都是回到订单页
  router.replace('/order')
}
</script>

<template>
  <div class="remark-page">
    <AppNavBar title="订单备注" @back="goBack" />

    <div class="remark-body">
      <el-input
        v-model="remark"
        type="textarea"
        :rows="5"
        :maxlength="MAX_LEN"
        :placeholder="PLACEHOLDER"
        resize="none"
        show-word-limit
      />
      <p class="remark-count">
        <span :class="{ tip: count === 0 }">{{ count }}</span>/{{ MAX_LEN }}
      </p>
    </div>

    <div class="remark-footer">
      <el-button type="warning" class="remark-submit" @click="save">完成</el-button>
    </div>
  </div>
</template>

<style scoped>
.remark-page {
  min-height: 100vh;
  padding-bottom: 72px;
  background: var(--sky-bg);
}

.remark-body {
  padding: 12px;
  margin: 10px;
  background: #fff;
  border-radius: 8px;
}

.remark-count {
  margin: 8px 0 0;
  color: var(--sky-text-sub);
  font-size: 12px;
  text-align: right;
}

.remark-count .tip {
  color: var(--sky-primary);
}

.remark-footer {
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

.remark-submit {
  width: 100%;
}
</style>
