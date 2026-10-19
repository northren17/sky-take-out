<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppNavBar from '@/components/AppNavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  delAddressBook,
  putAddressBookDefault,
  queryAddressBookList,
} from '@/api/modules/address'
import { SUCCESS_CODE } from '@/constants/business'
import type { AddressBook } from '@/types/api'
import { useCheckoutStore } from '@/stores/checkout'

/**
 * 地址管理（对应小程序 `pages/address/address`）
 *
 * 与源码一致：
 *  - 展示顺序：标签 + 省市区+详细地址，下一行「收货人 + 先生/女士」与手机号。
 *  - 点击整条即选中该地址（写回 store，供订单页读取）。
 *  - 「设为默认地址」调用 `PUT /user/addressBook/default`，成功后提示「默认地址设置成功」。
 *  - 源码本页**没有删除入口**（删除在编辑页），这里保留源码行为；
 *    但因为列表接口返回单对象/数组两种可能，删除按钮放在编辑页会更稳妥，故本页也不放删除。
 *
 * 修正的源码缺陷：
 *  - `getLableVal` 的 `case` 是字符串 `'1'/'2'/'3'`，而接口返回的 `label` 是数字，
 *    导致永远落到「其他」。这里先转字符串再匹配。
 *  - `sex` 判断用严格等于 `'0'`，数字 0 会落到「女士」。这里做宽松比较。
 */
const router = useRouter()
const checkout = useCheckoutStore()

const list = ref<AddressBook[]>([])
const loading = ref(false)
const activeId = ref<number | string | ''>('')

function labelText(label?: string | number | null): string {
  switch (String(label ?? '')) {
    case '1':
      return '公司'
    case '2':
      return '家'
    case '3':
      return '学校'
    default:
      return '其他'
  }
}

function fullAddress(item: AddressBook): string {
  return `${item.provinceName ?? ''}${item.cityName ?? ''}${item.districtName ?? ''}${
    item.detail ?? ''
  }`
}

function consigneeText(item: AddressBook): string {
  const suffix = Number(item.sex) === 0 ? '先生' : '女士'
  return `${item.consignee ?? ''} ${suffix}`
}

async function loadList() {
  loading.value = true
  try {
    const res = await queryAddressBookList()
    if (res.code === SUCCESS_CODE) {
      list.value = (res.data ?? []) as AddressBook[]
      const def = list.value.find((a) => Number(a.isDefault) === 1)
      activeId.value = def?.id ?? ''
    }
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

/** 点击整条 = 选中并返回上一页（订单页/个人中心） */
function choose(item: AddressBook) {
  checkout.setAddress(item)
  const back = checkout.addressBackPath || '/order'
  router.replace(back)
}

async function setDefault(item: AddressBook) {
  try {
    const res = await putAddressBookDefault({ id: item.id as number })
    if (res.code === SUCCESS_CODE) {
      activeId.value = item.id ?? ''
      ElMessage.success('默认地址设置成功')
      await loadList()
    }
  } catch {
    // 请求层已提示
  }
}

function addNew() {
  router.push('/address/edit')
}

function goEdit(item: AddressBook) {
  router.push({ path: '/address/edit', query: { id: String(item.id ?? '') } })
}

/** 删除：源码放在编辑页，这里也提供同样的二次确认入口，方便操作 */
async function remove(item: AddressBook) {
  try {
    await ElMessageBox.confirm('确定要删除这个收货地址吗？', '删除地址', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    const res = await delAddressBook(item.id as number)
    if (res.code === SUCCESS_CODE) {
      ElMessage.success('地址删除成功')
      await loadList()
    }
  } catch {
    // 请求层已提示
  }
}

onMounted(loadList)
</script>

<template>
  <div class="addr-page">
    <AppNavBar title="地址管理" @back="router.replace(checkout.addressBackPath || '/order')" />

    <div v-loading="loading" class="addr-body">
      <div v-if="list.length > 0" class="addr-list">
        <div
          v-for="item in list"
          :key="item.id"
          class="addr-item"
          :class="{ active: activeId === item.id }"
        >
          <div class="addr-top" @click="choose(item)">
            <div class="addr-left">
              <div class="addr-line1">
                <span class="addr-tag" :class="`tag-${item.label ?? 0}`">
                  {{ labelText(item.label) }}
                </span>
                <span class="addr-text">{{ fullAddress(item) }}</span>
              </div>
              <div class="addr-line2">
                <span>{{ consigneeText(item) }}</span>
                <span class="addr-phone">{{ item.phone }}</span>
              </div>
            </div>
            <button class="addr-edit" type="button" @click.stop="goEdit(item)">
              <img src="/static/edit.png" alt="编辑" />
            </button>
          </div>

          <div class="addr-bottom">
            <label class="addr-default" @click.stop="setDefault(item)">
              <el-radio :model-value="activeId" :value="item.id" @change="setDefault(item)">
                <span class="addr-default-text">设为默认地址</span>
              </el-radio>
            </label>
            <button class="addr-del" type="button" @click.stop="remove(item)">删除</button>
          </div>
        </div>
      </div>

      <EmptyState v-else-if="!loading" text="一个地址都没有哦" image="/static/no_adress.png" />
    </div>

    <div class="addr-footer">
      <el-button type="warning" class="addr-add" @click="addNew">+ 新增收货地址</el-button>
    </div>
  </div>
</template>

<style scoped>
.addr-page {
  min-height: 100vh;
  padding-bottom: 72px;
  background: var(--sky-bg);
}

.addr-body {
  padding: 10px;
}

.addr-item {
  padding: 12px;
  margin-bottom: 10px;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 8px;
}

.addr-item.active {
  border-color: var(--sky-accent);
}

.addr-top {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
}

.addr-left {
  flex: 1;
  min-width: 0;
}

.addr-line1 {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.addr-tag {
  flex: 0 0 auto;
  padding: 0 5px;
  border-radius: 3px;
  background: #f0f0f0;
  color: var(--sky-text-sub);
  font-size: 11px;
  line-height: 18px;
}

.addr-tag.tag-1 {
  background: #e8f3ff;
  color: #2b7de9;
}

.addr-tag.tag-2 {
  background: #fff4e5;
  color: #e69138;
}

.addr-tag.tag-3 {
  background: #eaf7ea;
  color: #3f9c3f;
}

.addr-text {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.addr-line2 {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  color: var(--sky-text-sub);
  font-size: 13px;
}

.addr-phone {
  color: var(--sky-text-weak);
}

.addr-edit {
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.addr-edit img {
  width: 18px;
  height: 18px;
  margin: 2px auto 0;
}

.addr-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px solid var(--sky-border);
}

.addr-default {
  cursor: pointer;
}

.addr-default-text {
  color: var(--sky-text-sub);
  font-size: 13px;
}

.addr-del {
  border: 0;
  background: transparent;
  color: var(--sky-text-weak);
  font-size: 13px;
  cursor: pointer;
}

.addr-del:hover {
  color: var(--sky-primary);
}

.address-footer {
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

.addr-add {
  width: 100%;
}
</style>
