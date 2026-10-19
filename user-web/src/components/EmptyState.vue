<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 提示文案 */
    text?: string
    /** 配图，默认用小程序的无数据图 */
    image?: string
    /** 图片尺寸 */
    imageSize?: number
  }>(),
  {
    text: '暂无数据',
    image: '/static/imgDefault.png',
    imageSize: 120,
  },
)
</script>

<template>
  <div class="empty-state">
    <img
      :src="image"
      :style="{ width: `${imageSize}px`, height: `${imageSize}px` }"
      alt=""
    />
    <p class="empty-text">{{ text }}</p>
    <slot />
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  text-align: center;
}

.empty-state img {
  object-fit: contain;
  opacity: 0.85;
}

.empty-text {
  margin: 12px 0 0;
  color: var(--sky-text-weak);
  font-size: 14px;
}
</style>
