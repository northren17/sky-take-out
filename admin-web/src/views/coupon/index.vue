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
            <el-table-column label="操作" width="260" fixed="right" class-name="admin-table-action-column">
              <template #default="{ row }">
                <div class="admin-table-actions">
                  <el-button type="primary" plain size="small" @click="openTemplate(row)">修改</el-button>
                  <el-button
                    type="danger"
                    plain
                    size="small"
                    :disabled="confirmingTemplateDelete || deletingTemplateId !== undefined"
                    :loading="deletingTemplateId === Number(row.id)"
                    @click="removeTemplate(row)"
                  >
                    删除
                  </el-button>
                  <el-button
                    :type="Number(row.status) === 1 ? 'warning' : 'success'"
                    plain
                    size="small"
                    @click="toggleTemplate(row)"
                  >
                    {{ Number(row.status) === 1 ? '禁用' : '启用' }}
                  </el-button>
                </div>
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
              <template #default="{ row }">
                {{ row.startTime && row.endTime ? `${row.startTime} 至 ${row.endTime}` : '未设置（草稿）' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">{{ activityStatusText(row) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right" class-name="admin-table-action-column">
              <template #default="{ row }">
                <div class="admin-table-actions">
                  <el-button type="primary" plain size="small" @click="openActivity(row)">编辑</el-button>
                  <el-button
                    :type="Number(row.activityStatus) === 1 ? 'warning' : 'success'"
                    plain
                    size="small"
                    :disabled="activityStatusText(row) === '已结束'"
                    :loading="updatingActivityId === Number(row.id)"
                    @click="toggleActivity(row)"
                  >
                    {{ Number(row.activityStatus) === 1 ? '暂停' : '开始' }}
                  </el-button>
                </div>
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
              <el-input
                v-model="exchangeCount"
                type="text"
                inputmode="numeric"
                autocomplete="off"
                placeholder="1-500"
                class="exchange-count-input"
              />
            </el-form-item>
            <el-form-item label="每码可兑换次数">
              <el-input
                v-model="exchangeMaxRedeemCount"
                type="text"
                inputmode="numeric"
                autocomplete="off"
                placeholder="1-10000"
                class="exchange-count-input"
              />
            </el-form-item>
            <el-form-item label="兑换码截止时间">
              <el-date-picker
                v-model="exchangeExpiresAt"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm"
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
      <el-form class="coupon-dialog-form" label-width="132px">
        <el-form-item label="模板名称"><el-input v-model="templateForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="优惠券类型">
          <el-select v-model="templateForm.couponType" @change="changeCouponType">
            <el-option label="满减券" :value="1" />
            <el-option label="折扣券" :value="2" />
            <el-option label="无门槛券" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="Number(templateForm.couponType) !== 3" label="优惠门槛（元）">
          <el-input v-model="templateNumericInputs.thresholdAmount" type="number" min="0" step="0.01" />
        </el-form-item>
        <el-form-item v-if="Number(templateForm.couponType) !== 2" label="优惠金额（元）">
          <el-input v-model="templateNumericInputs.discountAmount" type="number" min="0.01" step="0.01" />
        </el-form-item>
        <el-form-item v-if="Number(templateForm.couponType) === 2" label="折扣比例（0-1）">
          <el-input v-model="templateNumericInputs.discountRate" type="number" min="0.01" max="1" step="0.0001" />
        </el-form-item>
        <el-form-item v-if="Number(templateForm.couponType) === 2" label="最高优惠（元）">
          <el-input v-model="templateNumericInputs.maxDiscountAmount" type="number" min="0" step="0.01" />
        </el-form-item>
        <el-form-item label="有效期类型">
          <el-radio-group v-model="templateForm.validityType">
            <el-radio :value="1">领取后有效天数</el-radio>
            <el-radio :value="2">固定日期区间</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="templateForm.validityType === 1" label="有效天数">
          <el-input v-model="templateNumericInputs.validityDays" type="number" min="1" step="1" />
        </el-form-item>
        <el-form-item v-else label="有效日期">
          <el-date-picker
            v-model="validityRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-form-item label="适用范围">
          <el-select v-model="templateForm.scopeType" @change="changeScopeType">
            <el-option label="全部商品" :value="1" />
            <el-option label="指定菜品" :value="2" />
            <el-option label="指定菜品分类" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="Number(templateForm.scopeType) === 2" label="适用菜品">
          <el-select
            v-model="templateForm.scopeIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :loading="scopeOptionsLoading"
            placeholder="搜索并选择菜品"
          >
            <el-option
              v-for="item in dishOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="Number(templateForm.scopeType) === 3" label="适用分类">
          <el-select
            v-model="templateForm.scopeIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :loading="scopeOptionsLoading"
            placeholder="搜索并选择菜品分类"
          >
            <el-option
              v-for="item in categoryOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
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
        <el-form-item label="活动名称">
          <el-input v-model="activityForm.name" maxlength="100" @input="clearActivityFormError" />
        </el-form-item>
        <el-form-item label="优惠券模板">
          <el-select
            v-model="activityForm.templateId"
            :disabled="Boolean(activityForm.id)"
            @change="clearActivityFormError"
          >
            <el-option v-for="item in enabledTemplates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="selectedActivityTemplate"
          :title="`优惠规则：${ruleText(selectedActivityTemplate)}（在优惠券模板中设置）`"
          type="info"
          :closable="false"
          class="activity-rule-preview"
        />
        <el-form-item label="活动库存">
          <el-input
            v-model="activityNumericInputs.stockTotal"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            :disabled="Boolean(activityForm.id && activityHasClaims)"
            placeholder="请输入活动库存"
            @input="clearActivityFormError"
          />
        </el-form-item>
        <el-form-item label="每人限领">
          <el-input
            v-model="activityNumericInputs.perUserLimit"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            placeholder="请输入每人限领数量"
            @input="clearActivityFormError"
          />
        </el-form-item>
        <el-form-item label="活动时间">
          <el-date-picker
            v-model="activityRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
        </el-form-item>
        <el-alert v-if="activityForm.id && activityHasClaims" title="活动已有领取记录，总库存不可修改。" type="info" :closable="false" />
        <el-alert
          v-if="activityFormError"
          :title="activityFormError"
          type="error"
          :closable="false"
          class="activity-form-error"
        />
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
import { Delete } from '@element-plus/icons-vue'
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
import { getCategoryList, queryDishList } from '@/api/dish'

type DataRow = Record<string, any>

interface RequestFailure {
  message?: unknown
  response?: {
    status?: unknown
    data?: {
      msg?: unknown
      message?: unknown
      error?: unknown
    } | string
  }
}

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
const activityFormError = ref('')
const saving = ref(false)
const confirmingTemplateDelete = ref(false)
const deletingTemplateId = ref<number>()
const updatingActivityId = ref<number>()
const validityRange = ref<string[]>([])
const activityRange = ref<string[]>([])
const templateNumericInputs = ref({
  thresholdAmount: '0',
  discountAmount: '1',
  discountRate: '0.9',
  maxDiscountAmount: '0',
  validityDays: '7',
})
const activityNumericInputs = ref({
  stockTotal: '100',
  perUserLimit: '1',
})
const dishOptions = ref<{ id: number; name: string }[]>([])
const categoryOptions = ref<{ id: number; name: string }[]>([])
const scopeOptionsLoaded = ref(false)
const scopeOptionsLoading = ref(false)
const activityHasClaims = ref(false)
const exchangeActivityId = ref<number>()
const exchangeCount = ref('100')
const exchangeMaxRedeemCount = ref('1')
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
const selectedActivityTemplate = computed(() =>
  enabledTemplates.value.find((item) => Number(item.id) === Number(activityForm.value.templateId)),
)

function syncTemplateNumericInputs() {
  for (const field of Object.keys(templateNumericInputs.value) as Array<keyof typeof templateNumericInputs.value>) {
    templateNumericInputs.value[field] = String(templateForm.value[field] ?? '')
  }
}

function responseData(response: any) {
  return response?.data?.data ?? {}
}

function requestErrorMessage(error: unknown) {
  if (typeof error !== 'object' || error === null) return '请求失败'
  const failure = error as RequestFailure
  const responseData = failure.response?.data
  if (typeof responseData === 'string' && responseData.trim()) return responseData
  if (responseData && typeof responseData === 'object') {
    for (const detail of [responseData.msg, responseData.message, responseData.error]) {
      if (typeof detail === 'string' && detail.trim()) return detail
    }
  }
  if (typeof failure.message === 'string' && failure.message.trim()) return failure.message
  if (failure.response?.status) return `HTTP ${failure.response.status}`
  return '请求失败'
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
  } else {
    templateForm.value = { ...freshTemplate(), ...row }
    validityRange.value = row.validStartTime && row.validEndTime ? [row.validStartTime, row.validEndTime] : []
    templateForm.value.scopeIds = row.scopeData ? (JSON.parse(row.scopeData) as number[]) : []
  }
  syncTemplateNumericInputs()
  templateDialog.value = true
  void loadScopeOptions()
}

async function loadScopeOptions() {
  if (scopeOptionsLoaded.value || scopeOptionsLoading.value) return
  scopeOptionsLoading.value = true
  try {
    const [dishResponse, categoryResponse] = await Promise.all([
      queryDishList({}),
      getCategoryList({ type: 1 }),
    ])
    const dishes = responseData(dishResponse)
    const categories = responseData(categoryResponse)
    if (!Array.isArray(dishes) || !Array.isArray(categories)) {
      throw new Error('菜品或分类列表响应格式错误')
    }
    dishOptions.value = dishes
      .filter((item: DataRow) => Number.isFinite(Number(item.id)) && item.name)
      .map((item: DataRow) => ({ id: Number(item.id), name: String(item.name) }))
    categoryOptions.value = categories
      .filter((item: DataRow) => Number.isFinite(Number(item.id)) && item.name)
      .map((item: DataRow) => ({ id: Number(item.id), name: String(item.name) }))
    scopeOptionsLoaded.value = true
  } catch (error) {
    console.error('加载优惠券适用范围失败', error)
    ElMessage.error('菜品或分类加载失败，请重试')
  } finally {
    scopeOptionsLoading.value = false
  }
}

function changeCouponType(value: number) {
  if (Number(value) === 3) {
    templateForm.value.thresholdAmount = 0
    templateNumericInputs.value.thresholdAmount = '0'
  }
}

function changeScopeType() {
  templateForm.value.scopeIds = []
}

async function submitTemplate() {
  if (!templateForm.value.name?.trim()) return ElMessage.warning('请填写模板名称')
  if (Number(templateForm.value.scopeType) !== 1 && !templateForm.value.scopeIds.length) {
    return ElMessage.warning('请选择优惠券适用的菜品或分类')
  }
  const couponType = Number(templateForm.value.couponType)
  const validityType = Number(templateForm.value.validityType)
  const thresholdAmount = couponType === 3 ? 0 : Number(templateNumericInputs.value.thresholdAmount)
  const discountAmount =
    couponType === 2 ? Number(templateForm.value.discountAmount) : Number(templateNumericInputs.value.discountAmount)
  const discountRate =
    couponType === 2 ? Number(templateNumericInputs.value.discountRate) : Number(templateForm.value.discountRate)
  const maxDiscountAmount =
    couponType === 2
      ? Number(templateNumericInputs.value.maxDiscountAmount)
      : Number(templateForm.value.maxDiscountAmount)
  const validityDays =
    validityType === 1 ? Number(templateNumericInputs.value.validityDays) : Number(templateForm.value.validityDays)
  if (!Number.isFinite(thresholdAmount) || thresholdAmount < 0) {
    return ElMessage.warning('优惠门槛必须是大于或等于0的数字')
  }
  if (!Number.isFinite(discountAmount) || (couponType !== 2 && discountAmount <= 0)) {
    return ElMessage.warning('优惠金额必须大于0')
  }
  if (!Number.isFinite(discountRate) || discountRate <= 0 || discountRate > 1) {
    return ElMessage.warning('折扣比例须大于0且不超过1')
  }
  if (!Number.isFinite(maxDiscountAmount) || maxDiscountAmount < 0) {
    return ElMessage.warning('最高优惠必须是大于或等于0的数字')
  }
  if (validityType === 1 && (!Number.isInteger(validityDays) || validityDays < 1)) {
    return ElMessage.warning('有效天数必须是大于或等于1的整数')
  }
  const body = {
    ...templateForm.value,
    thresholdAmount,
    discountAmount,
    discountRate,
    maxDiscountAmount,
    validityDays,
    validStartTime: validityRange.value[0] || null,
    validEndTime: validityRange.value[1] || null,
    scopeIds: Number(templateForm.value.scopeType) === 1 ? [] : templateForm.value.scopeIds,
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
  if (confirmingTemplateDelete.value || deletingTemplateId.value !== undefined) return
  confirmingTemplateDelete.value = true
  try {
    await ElMessageBox.confirm(
      `确定删除优惠券模板“${row.name}”吗？`,
      '确认删除',
      {
        type: 'error',
        icon: Delete,
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonType: 'danger',
        cancelButtonType: 'default',
        confirmButtonClass: 'coupon-template-delete-confirm-button',
        cancelButtonClass: 'coupon-template-delete-cancel-button',
        customClass: 'coupon-template-delete-confirm',
        customStyle: {
          width: 'min(460px, calc(100vw - 32px))',
          boxSizing: 'border-box',
        },
        modalClass: 'coupon-template-delete-overlay',
        buttonSize: 'default',
        showClose: true,
        closeOnClickModal: false,
        closeOnPressEscape: true,
      },
    )
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    console.error('打开优惠券模板删除确认框失败', error)
    ElMessage.error(`删除操作未完成：${requestErrorMessage(error)}`)
    return
  } finally {
    confirmingTemplateDelete.value = false
  }

  const templateId = Number(row.id)
  deletingTemplateId.value = templateId
  try {
    const response = await deleteCouponTemplate(templateId)
    if (response?.data?.code !== 1) {
      const message = response?.data?.msg ?? response?.data?.message
      throw new Error(typeof message === 'string' && message.trim() ? message : '删除优惠券模板失败')
    }
    ElMessage.success('优惠券模板已删除')
    try {
      await loadTemplates()
    } catch (error) {
      console.error('删除优惠券模板后刷新列表失败', error)
      ElMessage.error(`删除成功，但优惠券列表刷新失败：${requestErrorMessage(error)}`)
    }
  } catch (error) {
    console.error('删除优惠券模板失败', error)
    ElMessage.error(`删除优惠券模板失败：${requestErrorMessage(error)}`)
  } finally {
    deletingTemplateId.value = undefined
  }
}

function openActivity(row?: DataRow) {
  activityFormError.value = ''
  activityHasClaims.value = Boolean(row && Number(row.issuedCount) > 0)
  activityForm.value = row
    ? { ...row, stockTotal: activityHasClaims.value ? row.stockRemaining + row.issuedCount : row.stockRemaining }
    : { name: '', templateId: undefined, stockTotal: 100, perUserLimit: 1 }
  activityNumericInputs.value = {
    stockTotal: String(activityForm.value.stockTotal ?? ''),
    perUserLimit: String(activityForm.value.perUserLimit ?? ''),
  }
  activityRange.value = row?.startTime && row?.endTime ? [row.startTime, row.endTime] : []
  activityDialog.value = true
  if (!enabledTemplates.value.length) void loadTemplates()
}

function clearActivityFormError() {
  activityFormError.value = ''
}

function showActivityFormError(message: string) {
  activityFormError.value = message
  ElMessage.warning(message)
}

async function submitActivity() {
  activityFormError.value = ''
  if (!activityForm.value.name?.trim()) {
    return showActivityFormError('请填写活动名称')
  }
  if (!activityForm.value.templateId) {
    return showActivityFormError('请选择优惠券模板')
  }
  const stockTotal = Number(activityNumericInputs.value.stockTotal)
  const perUserLimit = Number(activityNumericInputs.value.perUserLimit)
  if (!Number.isInteger(stockTotal) || stockTotal < 1) {
    return showActivityFormError('活动库存必须是大于0的整数')
  }
  if (!Number.isInteger(perUserLimit) || perUserLimit < 1) {
    return showActivityFormError('每人限领数量必须是大于0的整数')
  }
  const body = {
    ...activityForm.value,
    stockTotal,
    perUserLimit,
    startTime: activityRange.value[0] || null,
    endTime: activityRange.value[1] || null,
  }
  saving.value = true
  try {
    if (activityForm.value.id) await updateCouponActivity(body)
    else await saveCouponActivity(body)
    activityDialog.value = false
    await loadActivities()
    ElMessage.success('活动已保存为草稿')
  } catch (error) {
    console.error('保存优惠券活动失败', error)
    const message = requestErrorMessage(error)
    const scheduleHint = activityRange.value.length
      ? ''
      : ' 未设置活动时间的草稿需要先执行 migration-coupon-phase7.sql 并重启后端。'
    ElMessage.error(`活动保存失败：${message}。${scheduleHint}`)
  } finally {
    saving.value = false
  }
}

async function toggleActivity(row: DataRow) {
  const nextStatus = Number(row.activityStatus) === 1 ? 2 : 1
  const activityId = Number(row.id)
  updatingActivityId.value = activityId
  try {
    const response = await changeCouponActivityStatus(activityId, nextStatus)
    if (response?.data?.code !== 1) {
      const message = response?.data?.msg ?? response?.data?.message
      ElMessage.error(typeof message === 'string' && message.trim() ? message : '优惠券活动状态更新失败')
      return
    }
    await loadActivities()
    ElMessage.success(nextStatus === 1 ? '优惠券活动已开始' : '优惠券活动已暂停')
  } catch (error) {
    console.error('更新优惠券活动状态失败', error)
    ElMessage.error(`优惠券活动状态更新失败：${requestErrorMessage(error)}`)
  } finally {
    updatingActivityId.value = undefined
  }
}

async function generateCodes() {
  if (!exchangeActivityId.value) return ElMessage.warning('请选择兑换码关联活动')
  const count = Number(exchangeCount.value)
  const maxRedeemCount = Number(exchangeMaxRedeemCount.value)
  if (!Number.isInteger(count) || count < 1 || count > 500) {
    return ElMessage.warning('生成数量必须是1到500之间的整数')
  }
  if (!Number.isInteger(maxRedeemCount) || maxRedeemCount < 1 || maxRedeemCount > 10000) {
    return ElMessage.warning('每码可兑换次数必须是1到10000之间的整数')
  }
  saving.value = true
  try {
    const response = await generateCouponExchangeCodes({
      activityId: exchangeActivityId.value,
      count,
      maxRedeemCount,
      expiresAt: exchangeExpiresAt.value || null,
    })
    if (response?.data?.code !== 1) {
      const message = response?.data?.msg ?? response?.data?.message
      ElMessage.error(typeof message === 'string' && message.trim() ? message : '兑换码生成失败')
      return
    }
    const codes = responseData(response)
    if (!Array.isArray(codes) || !codes.length) return ElMessage.error('兑换码生成结果无效')
    exchangeCodes.value = codes
    ElMessage.success(`已生成 ${codes.length} 个兑换码`)
  } catch (error) {
    console.error('生成优惠券兑换码失败', error)
    ElMessage.error(`兑换码生成失败：${requestErrorMessage(error)}`)
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
  if (!row.startTime || !row.endTime) return '未设置时间'
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
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0 18px; }
.toolbar .el-input { width: 240px; }
@media (max-width: 640px) {
  .toolbar .el-input { flex: 1 1 100%; width: 100%; }
}
.exchange-count-input { width: 140px; }
.el-pagination { justify-content: flex-end; margin-top: 18px; }
.coupon-dialog-form :deep(.el-select),
.coupon-dialog-form :deep(.el-input),
.coupon-dialog-form :deep(.el-input-number) { width: 100%; }
.activity-rule-preview { margin: 0 0 18px 132px; width: calc(100% - 132px); }
.activity-form-error { margin: 0 0 18px 132px; width: calc(100% - 132px); }
</style>
