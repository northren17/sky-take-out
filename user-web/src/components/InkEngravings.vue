<script setup lang="ts">
/**
 * 食材版画：项目的菜品照片存在阿里云 OSS（我这台机器取不到，403），
 * 开放图库这次也连不上，按 skill「无网络」条款走第 4 档：**用代码画这个主体自己的东西**。
 *
 * 这一组图形只服务本页：一鱼、一椒、一菇、一笼、一壶、一碗、一锅、一碟，
 * 全部单墨（currentColor）、同一套笔触（2.5 单位线、圆头圆角），
 * 按印刷品的方式使用——压在纸色上，不做渐变、不做阴影、不做装饰形状。
 */
</script>

<template>
  <!-- 图形定义：只画本主体自己的东西 -->
  <svg class="engraving-defs" aria-hidden="true" focusable="false" width="0" height="0">
    <defs>
      <!-- 鱼：整条，带尾鳍与背鳍 -->
      <symbol id="ink-fish" viewBox="0 0 120 64">
        <path
          d="M14 34c14-16 34-24 56-24 12 0 22 3 30 8-6 4-9 10-9 16s3 12 9 16c-8 5-18 8-30 8-22 0-42-8-56-24Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path d="M100 18c8-6 14-8 16-8-1 6-1 12 0 18-2 0-8 0-16-2" fill="currentColor" />
        <path
          d="M44 12c4-8 12-11 20-11-3 6-3 12-1 17"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <circle cx="30" cy="30" r="2.6" fill="currentColor" />
        <path
          d="M40 34c6-3 12-3 18 0M52 44c6-3 12-3 18 0M66 24c6-3 12-3 18 0M78 34c5-2 10-2 15 0"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
      </symbol>

      <!-- 辣椒 -->
      <symbol id="ink-chilli" viewBox="0 0 120 64">
        <path
          d="M26 22c0-6 6-10 13-10 3 0 5 1 7 3 14 3 26 11 34 22 4 6 6 11 6 15 0 5-4 8-9 8-6 0-11-3-17-8-8-7-16-15-23-22-3-3-5-6-5-8Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M26 22c-5-2-9-6-10-11 5 1 10 3 14 6"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
      </symbol>

      <!-- 菌菇：一丛三朵 -->
      <symbol id="ink-mushroom" viewBox="0 0 120 64">
        <path
          d="M22 30c0-9 8-16 20-16s20 7 20 16Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M36 30v16c0 4 3 7 6 7s6-3 6-7V30"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M62 38c0-7 6-12 15-12s15 5 15 12Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M72 38v12c0 3 2 5 5 5s5-2 5-5V38"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <circle cx="34" cy="22" r="2.4" fill="currentColor" />
        <circle cx="72" cy="32" r="2" fill="currentColor" />
      </symbol>

      <!-- 蒸笼：一屉两格 -->
      <symbol id="ink-steamer" viewBox="0 0 120 64">
        <path
          d="M16 26h88v22c0 4-4 7-9 7H25c-5 0-9-3-9-7Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path d="M12 20h96v6H12Z" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linejoin="round" />
        <path
          d="M34 36h22M64 36h22"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
      </symbol>

      <!-- 茶壶 -->
      <symbol id="ink-teapot" viewBox="0 0 120 64">
        <path
          d="M30 24h44c8 0 14 6 14 13s-6 13-14 13H30c-8 0-14-6-14-13s6-13 14-13Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M88 32c8-2 14-6 18-12"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
        <path
          d="M88 42c6 2 11 6 14 12"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
        <path
          d="M44 24c0-8 5-13 12-13s12 5 12 13"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
      </symbol>

      <!-- 饭碗 -->
      <symbol id="ink-bowl" viewBox="0 0 120 64">
        <path
          d="M14 26h92c0 18-14 30-46 30S14 44 14 26Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path d="M8 22h104" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
        <path
          d="M42 12c-4 4-4 8 0 12M60 8c-4 5-4 9 0 13M78 12c-4 4-4 8 0 12"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
      </symbol>

      <!-- 炒锅 -->
      <symbol id="ink-wok" viewBox="0 0 120 64">
        <path
          d="M10 24h100c0 16-16 26-50 26S10 40 10 24Z"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
        <path
          d="M96 30h18"
          fill="none"
          stroke="currentColor"
          stroke-width="3"
          stroke-linecap="round"
        />
        <path
          d="M30 18c6-6 14-8 22-6M62 12c5-3 11-3 16 0"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
      </symbol>

      <!-- 碟与筷 -->
      <symbol id="ink-chopsticks" viewBox="0 0 120 64">
        <ellipse
          cx="60"
          cy="46"
          rx="42"
          ry="12"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
        />
        <ellipse cx="60" cy="42" rx="26" ry="7" fill="none" stroke="currentColor" stroke-width="2" />
        <path
          d="M18 34 96 6M26 34 104 6"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        />
      </symbol>
    </defs>
  </svg>
</template>

<style scoped>
.engraving-defs {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
}
</style>
