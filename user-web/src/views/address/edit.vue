<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import { REGION_DATA } from '@/data/region'
import {
  addAddressBook,
  delAddressBook,
  editAddressBook,
  queryAddressBookById,
} from '@/api/modules/address'
import { SUCCESS_CODE } from '@/constants/business'
import type { AddressBook } from '@/types/api'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 新增 / 编辑收货地址（对应小程序 `pages/addOrEditAddress/addOrEditAddress`）
 *
 * 与源码一致：
 *  - 表单字段：联系人、性别、手机号、收货地址（省市区 + 详细地址）、标签。
 *  - 性别是自定义单选：先生(0) / 女士(1)。
 *  - 标签三选一：公司(1) / 家(2) / 学校(3)，接口字段是 `label`。
 *  - 新增走 `POST /user/addressBook`；编辑走 `PUT /user/addressBook`（多传 `id`）。
 *  - 编辑态额外提供「删除地址」。
 *
 * 修正的源码缺陷：
 *  - 源码把省市区显示串存进 `address`，保存时用 `split("/")` 拆出
 *    `provinceName/cityName/districtName`；当某级名称本身含 "/" 时会拆错。
 *    网页端直接用级联组件输出的三段名称。
 *  - 源码校验里有一处 `.replace` 拼出的重复文案
 *    （`详细地址不能为空不能为空`），这里改为正常文案。
 */
const route = useRoute()
const router = useRouter()
const checkout = useCheckoutStore()

const editId = computed(() => (route.query.id ? String(route.query.id) : ''))
const isEdit = computed(() => editId.value !== '')
const pageTitle = computed(() => (isEdit.value ? '编辑收货地址' : '新增收货地址'))

const SEX_ITEMS = [
  { value: '0', name: '先生' },
  { value: '1', name: '女士' },
]
const LABEL_ITEMS = [
  { value: 1, name: '公司' },
  { value: 2, name: '家' },
  { value: 3, name: '学校' },
]

const form = reactive({
  name: '',
  sex: '0',
  phone: '',
  /** 级联选择结果 [省, 市, 区] */
  region: [] as string[],
  detail: '',
  label: 0 as number,
})

const saving = ref(false)

const PHONE_RE = /^1[3-9]\d{9}$/

function validate(): string {
  if (!form.name) return '联系人不能为空'
  if (!form.phone) return '手机号不能为空'
  if (!form.label) return '所属标签不能为空'
  if (form.region.length < 3) return '所在地区不能为空'
  if (!form.detail) return '详细地址不能为空'
  if (!PHONE_RE.test(form.phone)) return '手机号输入有误'
  if (form.name.length < 2 || form.name.length > 12) return '请输入合法的2-12个字符'
  return ''
}

async function loadDetail() {
  if (!isEdit.value) return
  try {
    const res = await queryAddressBookById({ id: editId.value })
    if (res.code === SUCCESS_CODE && res.data) {
      const data = res.data as AddressBook
      form.name = String(data.consignee ?? '')
      form.sex = String(data.sex ?? '0')
      form.phone = String(data.phone ?? '')
      form.detail = String(data.detail ?? '')
      form.label = Number(data.label ?? 0)
      form.region = [data.provinceName, data.cityName, data.districtName].filter(
        (v): v is string => Boolean(v),
      )
    }
  } catch {
    // 请求层已提示
  }
}

async function save() {
  const error = validate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  const [provinceName = '', cityName = '', districtName = ''] = form.region
  const payload = {
    consignee: form.name,
    phone: form.phone,
    sex: form.sex,
    provinceName,
    cityName,
    districtName,
    detail: form.detail,
    label: String(form.label),
  }

  saving.value = true
  try {
    const res = isEdit.value
      ? await editAddressBook({ ...payload, id: Number(editId.value) })
      : await addAddressBook(payload)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success(isEdit.value ? '地址修改成功' : '地址新增成功')
      // 回到来源页：订单页或地址列表
      const back = checkout.addressBackPath || '/address'
      await router.replace(back === '/order' ? '/address' : back)
    }
  } catch {
    // 请求层已提示
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!isEdit.value) return
  try {
    const res = await delAddressBook(editId.value)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success('地址删除成功')
      await router.replace('/address')
    }
  } catch {
    // 请求层已提示
  }
}

onMounted(loadDetail)
</script>

<template>
  <div class="addr-edit-page">
    <AppNavBar :title="pageTitle" @back="router.back()" />

    <div class="edit-body">
      <div class="form-item">
        <span class="form-label">联系人</span>
        <el-input v-model="form.name" placeholder="请填写收货人的姓名" maxlength="12" />
      </div>

      <div class="form-item">
        <span class="form-label">性别</span>
        <div class="sex-group">
          <button
            v-for="item in SEX_ITEMS"
            :key="item.value"
            class="sex-item"
            type="button"
            @click="form.sex = item.value"
          >
            <img
              :src="
                form.sex === item.value
                  ? '/static/icon-radio-selected.png'
                  : '/static/icon-radio.png'
              "
              alt=""
            />
            <span>{{ item.name }}</span>
          </button>
        </div>
      </div>

      <div class="form-item">
        <span class="form-label">手机号</span>
        <el-input
          v-model="form.phone"
          type="tel"
          maxlength="11"
          placeholder="请填写收货人手机号码"
        />
      </div>

      <div class="form-item column">
        <span class="form-label">收货地址</span>
        <el-cascader
          v-model="form.region"
          class="region-cascader"
          :options="REGION_DATA"
          :props="{ expandTrigger: 'hover' }"
          placeholder="省 / 市 / 区"
          clearable
        />
        <el-input
          v-model="form.detail"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="详细地址（精确到门牌号）"
        />
      </div>

      <div class="form-item">
        <span class="form-label">标签</span>
        <div class="label-group">
          <button
            v-for="item in LABEL_ITEMS"
            :key="item.value"
            class="label-chip"
            :class="{ active: form.label === item.value }"
            type="button"
            @click="form.label = item.value"
          >
            {{ item.name }}
          </button>
        </div>
      </div>
    </div>

    <div class="edit-footer">
      <el-button type="warning" class="edit-save" :loading="saving" @click="save">
        保存地址
      </el-button>
      <el-button v-if="isEdit" class="edit-del" @click="remove">删除地址</el-button>
    </div>
  </div>
</template>

<style scoped>
.addr-edit-page {
  min-height: 100vh;
  padding-bottom: 88px;
  background: #fff;
}

.edit-body {
  padding: 0 14px;
}

.form-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 0;
  border-bottom: 1px solid var(--sky-border);
}

.form-item.column {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.form-label {
  flex: 0 0 68px;
  color: var(--sky-text);
  font-size: 14px;
  font-weight: 600;
}

.form-item.column .form-label {
  flex: none;
}

.form-item :deep(.el-input),
.form-item :deep(.el-textarea) {
  flex: 1;
}

/* 性别 */
.sex-group {
  display: flex;
  gap: 18px;
}

.sex-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 14px;
  cursor: pointer;
}

.sex-item img {
  width: 16px;
  height: 16px;
}

/* 标签 */
.label-group {
  display: flex;
  gap: 8px;
}

.label-chip {
  height: 30px;
  padding: 0 14px;
  border: 1px solid var(--sky-border);
  border-radius: 6px;
  background: #fff;
  color: var(--sky-text-sub);
  font-size: 13px;
  cursor: pointer;
}

.label-chip.active {
  border-color: var(--sky-accent);
  background: var(--sky-accent);
  color: var(--sky-text);
  font-weight: 600;
}

.region-cascader {
  width: 100%;
}

/* 底部 */
.edit-footer {
  position: fixed;
  bottom: 0;
  left: 50%;
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: 1000px;
  padding: 10px 14px 14px;
  background: #fff;
  transform: translateX(-50%);
}

.edit-save,
.edit-del {
  width: 100%;
  margin-left: 0;
}
</style>
