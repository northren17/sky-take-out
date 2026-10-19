<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function refresh() {
  router.replace('/index')
}
</script>

<template>
  <div class="nonet">
    <img class="nonet-icon" src="/static/noNet.png" alt="" />
    <p class="nonet-title">网络无法连接</p>
    <button class="nonet-btn" type="button" @click="refresh">点击刷新</button>
  </div>
</template>

<style scoped>
.nonet {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 130px;
  text-align: center;
}

.nonet-icon {
  width: 150px;
  height: 150px;
  object-fit: contain;
}

.nonet-title {
  margin: 10px 0 0;
  font-size: 24px;
  color: var(--sky-text);
}

.nonet-btn {
  width: 124px;
  height: 36px;
  margin-top: 20px;
  border: 0;
  border-radius: 18px;
  background: linear-gradient(144deg, #ffda05 18%, #ffb302 80%);
  color: var(--sky-text);
  font-size: 15px;
  cursor: pointer;
}
</style>
