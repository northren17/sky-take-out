<template>
  <article class="dish-card">
    <button class="dish-thumb" type="button" :aria-label="`查看${name}详情`" @click="emit('open-detail')">
      <img v-if="image" v-image-fallback :src="image" :alt="name" class="dish-img" loading="lazy" />
      <div v-else class="dish-thumb-fallback">{{ name.slice(0, 2) }}</div>
    </button>

    <div class="dish-main">
      <div v-if="category" class="dish-category">{{ category }}</div>
      <h3 class="dish-name ellipsis">
        <button class="dish-name-button" type="button" @click="emit('open-detail')">{{ name }}</button>
      </h3>
      <p class="dish-desc ellipsis-2">{{ label }}</p>
      <div class="dish-meta" aria-label="评分与配送信息">
        <span><el-icon aria-hidden="true"><StarFilled /></el-icon>暂无评分</span>
        <i aria-hidden="true">·</i>
        <span><el-icon aria-hidden="true"><Van /></el-icon>外卖配送</span>
      </div>

      <div class="dish-foot">
        <p class="dish-price">
          <span class="symbol">￥</span>{{ priceText }}
        </p>

        <!-- 需要选规格 -->
        <div v-if="hasFlavor" class="dish-flavor-control">
          <span v-if="count > 0" class="dish-flavor-count">{{ count }}</span>
          <button
            class="dish-btn"
            type="button"
            :disabled="disabled || loading"
            @click="emit('choose-flavor')"
          >
            <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
            <template v-else>{{ count > 0 ? '选规格' : '选择规格' }}</template>
          </button>
        </div>

        <!-- 直接加减 -->
        <div v-else class="dish-stepper">
          <button
            v-if="showReduce && count > 0"
            class="stepper-btn minus"
            type="button"
            aria-label="减少"
            :disabled="disabled || loading"
            @click="emit('reduce')"
          >
            −
          </button>
          <span v-if="count > 0" class="stepper-count">{{ count }}</span>
          <button
            class="stepper-btn plus"
            type="button"
            aria-label="增加"
            :disabled="disabled || loading"
            @click="emit('add')"
          >
            <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
            <template v-else>+</template>
          </button>
        </div>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { fenToYuan } from '@/stores/cart'

const props = withDefaults(
  defineProps<{
    /** 商品名 */
    name?: string
    /** 描述，缺失时回退成名称（源码 `description || name`） */
    description?: string
    /** 图片地址（调用方负责传已拼好的完整地址） */
    image?: string
    /** 价格，按项目 API 金额原值展示 */
    price?: number
    /** 已加入购物车数量 */
    count?: number
    /** 是否需要选规格（源码：flavors 非空则显示「选择规格」） */
    hasFlavor?: boolean
    /** 是否展示减号（count > 0 时） */
    showReduce?: boolean
    /** 实际菜品分类名称 */
    category?: string
    /** 是否禁用加购操作 */
    disabled?: boolean
    /** 购物车请求状态 */
    loading?: boolean
  }>(),
  {
    name: '',
    description: '',
    image: '',
    price: 0,
    count: 0,
    hasFlavor: false,
    showReduce: true,
    category: '',
    disabled: false,
    loading: false,
  },
)

const emit = defineEmits<{
  (e: 'open-detail'): void
  (e: 'choose-flavor'): void
  (e: 'add'): void
  (e: 'reduce'): void
  (e: 'update:count', value: number): void
}>()

const priceText = computed(() => fenToYuan(props.price).toFixed(2))
const label = computed(() => props.description || props.name)
</script>

<style scoped>
.dish-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--sky-surface);
  border: 1px solid var(--sky-border-soft);
  border-radius: var(--sky-radius-lg);
  transition: transform 180ms var(--sky-ease), box-shadow 180ms var(--sky-ease);
}

.dish-card:hover {
  box-shadow: var(--sky-shadow);
  transform: translateY(-2px);
}

/* 图片区 */
.dish-thumb {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  padding: 0;
  border: 0;
  background: var(--sky-surface-2);
  cursor: pointer;
}

.dish-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 260ms var(--sky-ease);
}

.dish-card:hover .dish-img {
  transform: scale(1.04);
}

.dish-thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: var(--sky-surface-2);
  color: var(--sky-text-weak);
  font-size: 22px;
  font-weight: 600;
}

/* 信息区 */
.dish-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 12px 14px 14px;
}

.dish-category {
  overflow: hidden;
  margin-bottom: 5px;
  color: var(--sky-text-weak);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dish-name {
  min-height: 22px;
  margin: 0;
  color: var(--sky-text);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.dish-name-button {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dish-name-button:hover {
  color: var(--sky-primary);
}

.dish-desc {
  margin: 4px 0 0;
  min-height: 20px;
  color: var(--sky-text-weak);
  font-size: 12px;
  line-height: 1.55;
}

.dish-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--sky-text-weak);
  font-size: 11px;
}

.dish-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.dish-meta span:first-child .el-icon {
  color: #e8a328;
}

.dish-meta i {
  color: var(--sky-border);
  font-style: normal;
}

.dish-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: auto;
  padding-top: 10px;
}

.dish-flavor-control {
  display: flex;
  align-items: center;
  gap: 7px;
}

.dish-flavor-count {
  display: grid;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  place-items: center;
  border-radius: var(--sky-radius-pill);
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
  font-size: 11px;
  font-weight: 700;
}

.dish-price {
  margin: 0;
  color: var(--sky-price);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.dish-price .symbol {
  margin-right: 1px;
  font-size: 13px;
  font-weight: 500;
}

/* 选择规格按钮 */
.dish-btn {
  min-height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--sky-radius);
  background: var(--sky-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.25s var(--sky-ease), transform 0.25s var(--sky-ease);
}

.dish-btn:hover {
  background: var(--sky-primary-dark);
}

.dish-btn:focus-visible,
.stepper-btn:focus-visible {
  outline: 3px solid rgb(237 138 22 / 35%);
  outline-offset: 2px;
}

.dish-btn:disabled,
.stepper-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.dish-btn:active {
  transform: scale(0.96);
}

/* 加减器 */
.dish-stepper {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stepper-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  font-size: 19px;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
  transition: background 0.25s var(--sky-ease), transform 0.25s var(--sky-ease);
}

.stepper-btn:active {
  transform: scale(0.92);
}

.stepper-btn.plus {
  background: var(--sky-primary);
  color: #fff;
}

.stepper-btn.plus:hover {
  background: var(--sky-primary-dark);
}

.stepper-btn.minus {
  border: 1px solid var(--sky-border);
  background: transparent;
  color: var(--sky-text-sub);
}

.stepper-btn.minus:hover {
  background: var(--sky-surface-2);
}

.stepper-count {
  min-width: 18px;
  color: var(--sky-text);
  font-size: 15px;
  font-weight: 600;
  text-align: center;
}
</style>
