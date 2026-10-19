<template>
  <div class="dashboard-container coupon-page">
    <div class="container">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="优惠券模板" name="templates">
          <div class="toolbar">
            <el-input v-model="templateSearch" placeholder="模板名称" clearable @keyup.enter="loadTemplates" />
            <el-button type="primary" @click="loadTemplates">查询</el-button>
            <el-button type="primary" @click="openTemplate()">新建模板</el-button>
          </div>
          <el-table :data="templates" stripe>
            <el-table-column prop="name" label="名称" min-width="130" />
            <el-table-column label="类型" width="115">
              <template #default="{ row }">{{ couponTypeText(row.couponType) }}</template>
            </el-table-column>
            <el-table-column label="优惠规则" min-width="210">
              <template #default="{ row }">{{ ruleText(row) }}</template>
            </el-table-column>
            <el-table-column label="有效期" min-width="190">
              <template #default="{ row }">{{ validityText(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ Number(row.status) === 1 ? '启用' : '停用' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTemplate(row)">编辑</el-button>
                <el-button link type="primary" @click="toggleTemplate(row)">
                  {{ Number(row.status) === 1 ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="removeTemplate(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="templatePage"
            v-model:page-size="pageSize"
            :total="templateTotal"
            layout="total, sizes, prev, pager, next"
            @current-change="loadTemplates"
            @size-change="loadTemplates"
          />
        </el-tab-pane>
        <el-tab-pane label="抢券活动" name="activities">
          <div class="toolbar">
            <el-input v-model="activitySearch" placeholder="活动名称" clearable @keyup.enter="loadActivities" />
            <el-button type="primary" @click="loadActivities">查询</el-button>
            <el-button type="primary" @click="openActivity()">新建活动</el-button>
          </div>
          <el-table :data="activities" stripe>
            <el-table-column prop="name" label="活动名称" min-width="130" />
            <el-table-column prop="templateId" label="模板编号" width="100" />
            <el-table-column prop="issuedCount" label="已发放" width="90" />
            <el-table-column prop="usedCount" label="已使用" width="90" />
            <el-table-column prop="stockRemaining" label="剩余库存" width="100" />
            <el-table-column prop="perUserLimit" label="每人限领" width="95" />
            <el-table-column label="活动时间" min-width="280">
              <template #default="{ row }">{{ row.startTime }} 至 {{ row.endTime }}</template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">{{ activityStatusText(row) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openActivity(row)">编辑</el-button>
                <el-button
                  link
                  :type="Number(row.activityStatus) === 1 ? 'warning' : 'success'"
                  :disabled="activityStatusText(row) === '已结束'"
                  @click="toggleActivity(row)"
                >
                  {{ Number(row.activityStatus) === 1 ? '暂停' : '开始' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="activityPage"
            v-model:page-size="pageSize"
            :total="activityTotal"
            layout="total, sizes, prev, pager, next"
            @current-change="loadActivities"
            @size-change="loadActivities"
          />
        </el-tab-pane>
        <el-tab-pane label="兑换码" name="exchange">
          <el-form inline>
            <el-form-item label="关联活动">
              <el-select v-model="exchangeActivityId" placeholder="选择活动" filterable>
                <el-option v-for="item in activities" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="生成数量">
              <el-input-number v-model="exchangeCount" :min="1" :max="500" />
            </el-form-item>
            <el-form-item label="每码可兑换次数">
              <el-input-number v-model="exchangeMaxRedeemCount" :min="1" :max="10000" />
            </el-form-item>
            <el-form-item label="兑换码截止时间">
              <el-date-picker
                v-model="exchangeExpiresAt"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="不设置截止时间"
                clearable
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="generateCodes">生成兑换码</el-button>
            </el-form-item>
          </el-form>
          <el-alert title="每批最多500个；兑换码只在生成时展示，请及时复制保存。" type="info" :closable="false" />
          <el-input
            v-if="exchangeCodes.length"
            :model-value="exchangeCodeText"
            type="textarea"
            :rows="12"
            readonly
          />
          <el-button v-if="exchangeCodes.length" @click="copyExchangeCodes">复制兑换码</el-button>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="templateDialog" :title="templateForm.id ? '编辑优惠券模板' : '新建优惠券模板'" width="640px">
      <el-form label-width="132px">
        <el-form-item label="模板名称"><el-input v-model="templateForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="优惠券类型">
          <el-select v-model="templateForm.couponType">
            <el-option label="满减券" :value="1" />
            <el-option label="折扣券" :value="2" />
            <el-option label="无门槛券" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="优惠门槛（元）">
          <el-input-number v-model="templateForm.thresholdAmount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="优惠金额（元）" v-if="templateForm.couponType !== 2">
          <el-input-number v-model="templateForm.discountAmount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="折扣比例（0-1）" v-if="templateForm.couponType === 2">
          <el-input-number v-model="templateForm.discountRate" :min="0.01" :max="1" :step="0.05" :precision="4" />
        </el-form-item>
        <el-form-item label="最高优惠（元）" v-if="templateForm.couponType === 2">
          <el-input-number v-model="templateForm.maxDiscountAmount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="有效期类型">
          <el-radio-group v-model="templateForm.validityType">
            <el-radio :value="1">领取后有效天数</el-radio>
            <el-radio :value="2">固定日期区间</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="templateForm.validityType === 1" label="有效天数">
          <el-input-number v-model="templateForm.validityDays" :min="1" />
        </el-form-item>
        <el-form-item v-else label="有效日期">
          <el-date-picker
            v-model="validityRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-form-item label="适用范围">
          <el-select v-model="templateForm.scopeType">
            <el-option label="全部商品" :value="1" />
            <el-option label="指定菜品编号" :value="2" />
            <el-option label="指定分类编号" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="templateForm.scopeType !== 1" label="范围编号">
          <el-input v-model="scopeIdsText" placeholder="多个编号以英文逗号分隔" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="templateForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitTemplate">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="activityDialog" :title="activityForm.id ? '编辑抢券活动' : '新建抢券活动'" width="620px">
      <el-form label-width="132px">
        <el-form-item label="活动名称"><el-input v-model="activityForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="优惠券模板">
          <el-select v-model="activityForm.templateId" :disabled="Boolean(activityForm.id)">
            <el-option v-for="item in enabledTemplates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="活动库存">
          <el-input-number v-model="activityForm.stockTotal" :min="1" :disabled="Boolean(activityForm.id && activityHasClaims)" />
        </el-form-item>
        <el-form-item label="每人限领"><el-input-number v-model="activityForm.perUserLimit" :min="1" /></el-form-item>
        <el-form-item label="活动时间">
          <el-date-picker
            v-model="activityRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-alert v-if="activityForm.id && activityHasClaims" title="活动已有领取记录，总库存不可修改。" type="info" :closable="false" />
      </el-form>
      <template #footer>
        <el-button @click="activityDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitActivity">保存为草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeCouponActivityStatus,
  changeCouponTemplateStatus,
  deleteCouponTemplate,
  generateCouponExchangeCodes,
  getCouponActivities,
  getCouponTemplates,
  saveCouponActivity,
  saveCouponTemplate,
  updateCouponActivity,
  updateCouponTemplate,
} from '@/api/coupon'

type DataRow = Record<string, any>

const activeTab = ref('templates')
const pageSize = ref(10)
const templatePage = ref(1)
const templateTotal = ref(0)
const templates = ref<DataRow[]>([])
const templateSearch = ref('')
const activityPage = ref(1)
const activityTotal = ref(0)
const activities = ref<DataRow[]>([])
const activitySearch = ref('')
const templateDialog = ref(false)
const activityDialog = ref(false)
const saving = ref(false)
const validityRange = ref<string[]>([])
const activityRange = ref<string[]>([])
const scopeIdsText = ref('')
const activityHasClaims = ref(false)
const exchangeActivityId = ref<number>()
const exchangeCount = ref(100)
const exchangeMaxRedeemCount = ref(1)
const exchangeExpiresAt = ref('')
const exchangeCodes = ref<string[]>([])
const exchangeCodeText = computed(() => exchangeCodes.value.join('\n'))

const freshTemplate = () => ({
  name: '',
  couponType: 1,
  thresholdAmount: 0,
  discountAmount: 1,
  discountRate: 0.9,
  maxDiscountAmount: 0,
  validityType: 1,
  validityDays: 7,
  validStartTime: '',
  validEndTime: '',
  scopeType: 1,
  scopeIds: [] as number[],
  status: 0,
})
const templateForm = ref<DataRow>(freshTemplate())
const activityForm = ref<DataRow>({
  name: '',
  templateId: undefined,
  stockTotal: 100,
  perUserLimit: 1,
})
const enabledTemplates = computed(() => templates.value.filter((item) => Number(item.status) === 1))

function responseData(response: any) {
  return response?.data?.data ?? {}
}

async function loadTemplates() {
  const result = responseData(await getCouponTemplates({
    page: templatePage.value,
    pageSize: pageSize.value,
    name: templateSearch.value || undefined,
  }))
  templates.value = result.records ?? []
  templateTotal.value = Number(result.total ?? 0)
}

async function loadActivities() {
  const result = responseData(await getCouponActivities({
    page: activityPage.value,
    pageSize: pageSize.value,
    name: activitySearch.value || undefined,
  }))
  activities.value = result.records ?? []
  activityTotal.value = Number(result.total ?? 0)
  if (!enabledTemplates.value.length) await loadTemplates()
}

function openTemplate(row?: DataRow) {
  if (!row) {
    templateForm.value = freshTemplate()
    validityRange.value = []
    scopeIdsText.value = ''
  } else {
    templateForm.value = { ...freshTemplate(), ...row }
    validityRange.value = row.validStartTime && row.validEndTime ? [row.validStartTime, row.validEndTime] : []
    scopeIdsText.value = row.scopeData ? (JSON.parse(row.scopeData) as number[]).join(',') : ''
  }
  templateDialog.value = true
}

async function submitTemplate() {
  if (!templateForm.value.name?.trim()) return ElMessage.warning('请填写模板名称')
  const scopeIds = scopeIdsText.value.split(',').map((item) => Number(item.trim())).filter(Number.isFinite)
  const body = {
    ...templateForm.value,
    validStartTime: validityRange.value[0] || null,
    validEndTime: validityRange.value[1] || null,
    scopeIds,
  }
  saving.value = true
  try {
    if (templateForm.value.id) await updateCouponTemplate(body)
    else await saveCouponTemplate(body)
    templateDialog.value = false
    await loadTemplates()
    ElMessage.success('模板已保存')
  } finally {
    saving.value = false
  }
}

async function toggleTemplate(row: DataRow) {
  await changeCouponTemplateStatus(Number(row.id), Number(row.status) === 1 ? 0 : 1)
  await loadTemplates()
}

async function removeTemplate(row: DataRow) {
  await ElMessageBox.confirm(`确定删除优惠券模板“${row.name}”吗？`, '确认删除')
  await deleteCouponTemplate(Number(row.id))
  await loadTemplates()
}

function openActivity(row?: DataRow) {
  activityHasClaims.value = Boolean(row && Number(row.issuedCount) > 0)
  activityForm.value = row
    ? { ...row, stockTotal: activityHasClaims.value ? row.stockRemaining + row.issuedCount : row.stockRemaining }
    : { name: '', templateId: undefined, stockTotal: 100, perUserLimit: 1 }
  activityRange.value = row ? [row.startTime, row.endTime] : []
  activityDialog.value = true
  if (!enabledTemplates.value.length) void loadTemplates()
}

async function submitActivity() {
  if (!activityForm.value.name?.trim() || !activityForm.value.templateId || activityRange.value.length !== 2) {
    return ElMessage.warning('请完整填写活动名称、模板和时间')
  }
  const body = {
    ...activityForm.value,
    startTime: activityRange.value[0],
    endTime: activityRange.value[1],
  }
  saving.value = true
  try {
    if (activityForm.value.id) await updateCouponActivity(body)
    else await saveCouponActivity(body)
    activityDialog.value = false
    await loadActivities()
    ElMessage.success('活动已保存为草稿')
  } finally {
    saving.value = false
  }
}

async function toggleActivity(row: DataRow) {
  const nextStatus = Number(row.activityStatus) === 1 ? 2 : 1
  await changeCouponActivityStatus(Number(row.id), nextStatus)
  await loadActivities()
}

async function generateCodes() {
  if (!exchangeActivityId.value) return ElMessage.warning('请选择兑换码关联活动')
  saving.value = true
  try {
    const response = await generateCouponExchangeCodes({
      activityId: exchangeActivityId.value,
      count: exchangeCount.value,
      maxRedeemCount: exchangeMaxRedeemCount.value,
      expiresAt: exchangeExpiresAt.value || null,
    })
    const codes = responseData(response)
    if (!Array.isArray(codes) || !codes.length) return ElMessage.error('兑换码生成结果无效')
    exchangeCodes.value = codes
    ElMessage.success(`已生成 ${codes.length} 个兑换码`)
  } finally {
    saving.value = false
  }
}

async function copyExchangeCodes() {
  await navigator.clipboard.writeText(exchangeCodeText.value)
  ElMessage.success('兑换码已复制')
}

function couponTypeText(type: number) {
  return ({ 1: '满减券', 2: '折扣券', 3: '无门槛券' } as Record<number, string>)[Number(type)] ?? '未知'
}

function ruleText(row: DataRow) {
  if (Number(row.couponType) === 2) return `满${row.thresholdAmount}元打${Number(row.discountRate) * 10}折`
  return `${Number(row.couponType) === 3 ? '无门槛' : `满${row.thresholdAmount}元`}减${row.discountAmount}元`
}

function validityText(row: DataRow) {
  return Number(row.validityType) === 1
    ? `领取后${row.validityDays}天`
    : `${row.validStartTime ?? '-'} 至 ${row.validEndTime ?? '-'}`
}

function activityStatusText(row: DataRow) {
  const now = Date.now()
  if (Number(row.activityStatus) === 0) return '草稿'
  if (Number(row.activityStatus) === 2) return '已暂停'
  if (Date.parse(row.startTime.replace(' ', 'T')) > now) return '未开始'
  if (Date.parse(row.endTime.replace(' ', 'T')) <= now || Number(row.stockRemaining) <= 0) return '已结束'
  return '进行中'
}

onMounted(async () => {
  await loadTemplates()
  await loadActivities()
})
</script>

<style scoped>
.coupon-page .container { padding: 20px; background: #fff; }
.toolbar { display: flex; gap: 10px; margin: 12px 0 18px; }
.toolbar .el-input { width: 240px; }
.el-pagination { justify-content: flex-end; margin-top: 18px; }
</style>
