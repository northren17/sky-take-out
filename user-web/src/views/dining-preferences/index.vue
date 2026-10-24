<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Coin, Dish } from '@element-plus/icons-vue'
import { getDiningPreferences, saveDiningPreferences } from '@/api/modules/user'
import { SUCCESS_CODE } from '@/constants/business'

const router = useRouter()
const tasteOptions = ['清淡', '微辣', '中辣', '重辣', '甜口', '少油', '素食']
const tastes = ref<string[]>([])
const dailyBudget = ref<number>()
const spentToday = ref(0)
const loading = ref(false)
const saving = ref(false)
const remainingBudget = computed(() =>
  dailyBudget.value === undefined ? null : Math.max(0, dailyBudget.value - spentToday.value),
)

async function loadPreferences() {
  loading.value = true
  try {
    const response = await getDiningPreferences()
    if (response.code !== SUCCESS_CODE || !response.data) {
      throw new Error(response.msg || '读取饮食偏好失败')
    }
    tastes.value = response.data.tastes ?? []
    dailyBudget.value = response.data.dailyBudget ?? undefined
    spentToday.value = Number(response.data.spentToday ?? 0)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取饮食偏好失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function savePreferences() {
  if (dailyBudget.value === undefined || dailyBudget.value <= 0) {
    ElMessage.warning('请设置大于 0 元的每日餐费限额')
    return
  }
  saving.value = true
  try {
    const response = await saveDiningPreferences({
      tastes: tastes.value,
      dailyBudget: dailyBudget.value,
    })
    if (response.code !== SUCCESS_CODE || !response.data) {
      throw new Error(response.msg || '保存饮食偏好失败')
    }
    spentToday.value = Number(response.data.spentToday ?? 0)
    dailyBudget.value = response.data.dailyBudget ?? dailyBudget.value
    ElMessage.success('口味与每日预算已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(loadPreferences)
</script>

<template>
  <main v-loading="loading" class="preference-page">
    <header class="page-heading">
      <button class="back-button" type="button" aria-label="返回个人中心" @click="router.push('/my')">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div>
        <p class="eyebrow">YOUR DINING PROFILE</p>
        <h1>口味与每日预算</h1>
        <p>设置喜欢的口味和每日餐费限额，首页会据此筛选适合你的菜品。</p>
      </div>
    </header>

    <section class="preference-card">
      <div class="card-title">
        <span class="title-icon"><el-icon><Dish /></el-icon></span>
        <div>
          <h2>喜欢的口味</h2>
          <p>可多选，推荐时优先展示名称或介绍符合偏好的菜品</p>
        </div>
      </div>
      <el-checkbox-group v-model="tastes" class="taste-options" aria-label="口味偏好">
        <el-checkbox v-for="taste in tasteOptions" :key="taste" :value="taste" border>
          {{ taste }}
        </el-checkbox>
      </el-checkbox-group>
    </section>

    <section class="preference-card budget-card">
      <div class="card-title">
        <span class="title-icon"><el-icon><Coin /></el-icon></span>
        <div>
          <h2>每日餐费限额</h2>
          <p>按当天已支付订单扣减额度，推荐价格不超过今日剩余额度的菜品。</p>
        </div>
      </div>
      <div class="budget-input-row">
        <label for="daily-budget">每天最多花费</label>
        <el-input-number
          id="daily-budget"
          v-model="dailyBudget"
          :min="0.01"
          :max="10000"
          :precision="2"
          :step="5"
          controls-position="right"
          placeholder="请输入金额"
        />
        <span>元 / 天</span>
      </div>
      <div class="budget-summary">
        <div>
          <span>今日已支付</span>
          <strong>￥{{ spentToday.toFixed(2) }}</strong>
        </div>
        <div>
          <span>今日剩余额度</span>
          <strong class="remaining-amount">
            {{ remainingBudget === null ? '未设置' : `￥${remainingBudget.toFixed(2)}` }}
          </strong>
        </div>
      </div>
    </section>

    <footer class="page-actions">
      <el-button @click="router.push('/index')">返回首页</el-button>
      <el-button type="primary" :loading="saving" @click="savePreferences">保存设置</el-button>
    </footer>
  </main>
</template>

<style scoped>
.preference-page {
  max-width: 1040px;
  min-height: calc(100vh - 80px);
  margin: 0 auto;
  padding: 42px 36px 112px;
  color: #1f352b;
  background: #f6f8f5;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  margin-bottom: 28px;
}

.back-button {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border: 1px solid #dce7df;
  border-radius: 12px;
  background: #fff;
  color: #23764c;
  cursor: pointer;
}

.eyebrow {
  margin: 0 0 8px;
  color: #43805d;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

h1,
h2,
p {
  margin-top: 0;
}

.page-heading h1 {
  margin-bottom: 8px;
  font-size: 28px;
}

.page-heading p:last-child,
.card-title p {
  margin-bottom: 0;
  color: #7b8b81;
  font-size: 14px;
}

.preference-card {
  margin-bottom: 18px;
  padding: 26px;
  border: 1px solid #e8eee9;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 10px 28px rgb(33 71 49 / 6%);
}

.card-title {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
}

.card-title h2 {
  margin-bottom: 7px;
  font-size: 18px;
}

.title-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 14px;
  background: #eaf5ed;
  color: #23764c;
  font-size: 20px;
}

.taste-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.taste-options :deep(.el-checkbox) {
  margin-right: 0;
}

.budget-input-row {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #43564a;
  font-size: 14px;
}

.budget-input-row :deep(.el-input-number) {
  width: 220px;
}

.budget-summary {
  display: flex;
  gap: 48px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #edf1ed;
}

.budget-summary div {
  display: grid;
  gap: 8px;
}

.budget-summary span {
  color: #829087;
  font-size: 13px;
}

.budget-summary strong {
  font-size: 20px;
}

.budget-summary .remaining-amount {
  color: #23764c;
}

.page-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.page-actions :deep(.el-button--primary) {
  --el-color-primary: #23764c;
  --el-color-primary-light-3: #55946f;
  --el-color-primary-light-5: #83b197;
  --el-color-primary-light-7: #b2cdbd;
  --el-color-primary-light-8: #d0e1d6;
  --el-color-primary-light-9: #eaf5ed;
}

@media (max-width: 640px) {
  .preference-page {
    padding: 28px 16px 100px;
  }

  .preference-card {
    padding: 20px 16px;
  }

  .budget-input-row {
    flex-wrap: wrap;
  }

  .budget-summary {
    justify-content: space-between;
    gap: 16px;
  }
}
</style>
