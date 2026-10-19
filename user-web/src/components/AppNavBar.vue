<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

withDefaults(
  defineProps<{
    /** 标题；不传则不显示标题 */
    title?: string
    /** 是否显示返回箭头 */
    showBack?: boolean
    /** 导航栏背景色 */
    background?: string
    /** 文字颜色 */
    color?: string
    /** 是否固定在顶部 */
    fixed?: boolean
  }>(),
  {
    title: '',
    showBack: true,
    background: '#ffffff',
    color: 'var(--sky-text)',
    fixed: true,
  },
)

const emit = defineEmits<{ (e: 'back'): void }>()
const router = useRouter()

function onBack() {
  emit('back')
  if (window.history.length > 1) router.back()
  else router.replace('/index')
}
</script>

<template>
  <header
    class="app-navbar"
    :class="{ 'is-fixed': fixed }"
    :style="{ background, color }"
  >
    <button v-if="showBack" class="nav-back" type="button" aria-label="返回" @click="onBack">
      <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
    </button>
    <h1 v-if="title" class="nav-title ellipsis">{{ title }}</h1>
    <div class="nav-extra">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped>
.app-navbar {
  z-index: 20;
  display: flex;
  align-items: center;
  min-height: 64px;
  padding: 0 16px;
  border-bottom: 1px solid var(--sky-border-soft);
  box-shadow: var(--sky-shadow-sm);
  font-size: 16px;
  font-weight: 600;
}

.app-navbar.is-fixed {
  position: sticky;
  top: var(--sky-header-offset, 0px);
  width: 100%;
  background: var(--sky-surface);
}

.nav-back {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.nav-back:hover {
  background: var(--sky-surface-2);
}

.nav-title {
  flex: 1;
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  text-align: left;
}

.nav-extra {
  display: flex;
  align-items: center;
  min-width: 40px;
}
</style>
