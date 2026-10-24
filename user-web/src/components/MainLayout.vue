<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, type Component } from 'vue'
import {
  ArrowLeft,
  ArrowRight,
  Calendar,
  ChatLineRound,
  Discount,
  Dish,
  House,
  Location,
  Present,
  Tickets,
} from '@element-plus/icons-vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'
import { useUserStore } from '@/stores/user'
import VoiceAssistant from '@/components/VoiceAssistant.vue'

const route = useRoute()
const checkout = useCheckoutStore()
const shop = useShopStore()
const user = useUserStore()
const isSidebarCollapsed = ref(window.innerWidth <= 900)
const voiceAssistantEnabled = import.meta.env.VITE_ENABLE_VOICE_ASSISTANT !== 'false'

interface NavItem {
  name: string
  to: RouteLocationRaw
  icon: Component
  active: () => boolean
}

const navItems: NavItem[] = [
  {
    name: '首页',
    to: { path: '/index' },
    icon: House,
    active: () => route.path === '/index' && route.hash !== '#menu',
  },
  {
    name: '点餐',
    to: { path: '/index', hash: '#menu' },
    icon: Dish,
    active: () => route.path === '/index' && route.hash === '#menu',
  },
  {
    name: '套餐专区',
    to: { path: '/meals' },
    icon: Present,
    active: () => route.path === '/meals',
  },
  {
    name: '预订点餐',
    to: { path: '/reservation' },
    icon: Calendar,
    active: () => route.path === '/reservation',
  },
  {
    name: '评价晒单',
    to: { path: '/reviews' },
    icon: ChatLineRound,
    active: () => route.path === '/reviews',
  },
  {
    name: '我的订单',
    to: { path: '/history' },
    icon: Tickets,
    active: () =>
      route.path === '/history' ||
      route.path === '/order' ||
      route.path.startsWith('/order/detail/') ||
      route.path.startsWith('/pay/') ||
      route.path.startsWith('/success/'),
  },
  {
    name: '地址管理',
    to: { path: '/address' },
    icon: Location,
    active: () => route.path.startsWith('/address'),
  },
  {
    name: '领券中心',
    to: { path: '/coupons' },
    icon: Discount,
    active: () => route.path === '/coupons',
  },
]

const displayName = computed(
  () => user.baseUserInfo.nickName || user.account || '个人中心',
)

function onNavigate(item: NavItem) {
  if (item.name === '地址管理') checkout.setAddressBackPath('/index')
}

function toggleSidebar() {
  isSidebarCollapsed.value = !isSidebarCollapsed.value
}

function handleResize() {
  if (window.innerWidth <= 900) isSidebarCollapsed.value = true
}

onMounted(() => window.addEventListener('resize', handleResize))
onBeforeUnmount(() => window.removeEventListener('resize', handleResize))
</script>

<template>
  <div class="main-layout" :class="{ 'is-sidebar-collapsed': isSidebarCollapsed }">
    <div class="workspace-shell">
      <aside class="side-rail" aria-label="网站导航">
        <div class="rail-heading">
          <RouterLink class="rail-brand" to="/index" aria-label="智享云膳首页">
            <img src="/static/brand-mark.svg" alt="" />
            <span>
              <strong>智享云膳</strong>
              <small>鲜食 · 好味 · 即刻享</small>
            </span>
          </RouterLink>
          <button
            class="rail-collapse-toggle"
            type="button"
            :aria-label="isSidebarCollapsed ? '展开导航栏' : '收起导航栏'"
            :aria-expanded="!isSidebarCollapsed"
            @click="toggleSidebar"
          >
            <el-icon aria-hidden="true">
              <component :is="isSidebarCollapsed ? ArrowRight : ArrowLeft" />
            </el-icon>
          </button>
        </div>
        <nav class="rail-nav" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.name"
            :to="item.to"
            class="rail-link"
            :class="{ active: item.active() }"
            :aria-current="item.active() ? 'page' : undefined"
            :aria-label="item.name"
            :title="item.name"
            @click="onNavigate(item)"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            <span>{{ item.name }}</span>
          </RouterLink>
        </nav>
      </aside>

      <div class="workspace-column">
        <header class="layout-header">
          <div class="header-content">
            <RouterLink class="mobile-brand" to="/index" aria-label="智享云膳首页">
              <img src="/static/brand-mark.svg" alt="" />
              <strong>智享云膳</strong>
            </RouterLink>
            <div
              class="header-shop-state"
              :class="{
                'is-open': route.path === '/index' && shop.isOpen,
                'is-closed': route.path === '/index' && shop.isClosed,
              }"
            >
              <i aria-hidden="true" />
              <span>
                {{
                  route.path !== '/index'
                    ? '智享云膳'
                    : shop.isOpen
                      ? '营业中'
                      : shop.isClosed
                        ? '本店已打烊'
                        : '营业状态加载中'
                }}
              </span>
            </div>
            <RouterLink class="header-account" to="/my">
              <el-icon aria-hidden="true"><User /></el-icon>
              <span>{{ displayName }}</span>
            </RouterLink>
          </div>
        </header>

        <main id="main-content" class="layout-main">
          <div class="content-container">
            <slot />
          </div>
        </main>
      </div>
    </div>
    <VoiceAssistant v-if="voiceAssistantEnabled && user.token" />
  </div>
</template>

<style scoped>
.main-layout {
  --sky-sidebar-width: 248px;
  min-height: 100dvh;
  padding: 24px;
  background: #edf0f5;
}

.main-layout.is-sidebar-collapsed {
  --sky-sidebar-width: 96px;
}

.workspace-shell {
  display: grid;
  grid-template-columns: var(--sky-sidebar-width) minmax(0, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 1600px;
  min-height: calc(100dvh - 48px);
  margin-inline: auto;
  overflow: visible;
  border: 1px solid rgb(255 255 255 / 78%);
  border-radius: 26px;
  background: var(--sky-surface);
  box-shadow: 0 20px 60px rgb(71 100 137 / 12%);
}

.side-rail {
  position: fixed;
  top: 36px;
  left: max(36px, calc((100vw - 1600px) / 2 + 36px));
  z-index: 40;
  display: flex;
  width: calc(var(--sky-sidebar-width) - 12px);
  flex-direction: column;
  height: calc(100dvh - 72px);
  min-height: 0;
  padding: 18px 12px 14px;
  border: 1px solid rgb(255 255 255 / 78%);
  border-radius: 22px;
  background: linear-gradient(180deg, #0e6655 0%, #0b594b 100%);
  color: #fff;
  box-shadow: 0 14px 36px rgb(33 62 49 / 16%);
  transition: width 180ms ease, box-shadow 180ms ease;
}

.rail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 54px;
  padding: 0 5px;
  color: #fff;
  text-decoration: none;
}

.rail-collapse-toggle {
  display: inline-flex;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid rgb(255 255 255 / 18%);
  border-radius: 9px;
  background: rgb(255 255 255 / 8%);
  color: #fff;
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease,
    transform 300ms var(--sky-press-release);
}

.rail-collapse-toggle:hover {
  border-color: rgb(255 255 255 / 32%);
  background: rgb(255 255 255 / 16%);
}

.rail-collapse-toggle:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

.rail-brand img {
  flex: 0 0 42px;
  width: 42px;
  height: 42px;
  padding: 5px;
  border: 1px solid rgb(255 255 255 / 18%);
  border-radius: 13px;
  background: rgb(255 255 255 / 12%);
  object-fit: contain;
}

.rail-brand span {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.rail-brand strong {
  font-size: 15px;
  font-weight: 750;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.rail-brand small {
  color: rgb(255 255 255 / 66%);
  font-size: 10px;
  white-space: nowrap;
}

.mobile-brand img {
  width: 34px;
  height: 34px;
  object-fit: contain;
}

.rail-nav {
  display: grid;
  min-height: 0;
  gap: 6px;
  align-content: start;
  padding-top: 22px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}

.rail-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 13px;
  min-height: 46px;
  padding: 0 13px;
  border: 1px solid transparent;
  border-radius: 11px;
  color: rgb(255 255 255 / 78%);
  font-size: 14px;
  font-weight: 550;
  text-decoration: none;
  transition: color 160ms ease, background 160ms ease, border-color 160ms ease,
    transform 300ms var(--sky-press-release);
}

.rail-link .el-icon {
  flex: 0 0 auto;
  font-size: 19px;
}

.is-sidebar-collapsed .rail-heading {
  flex-direction: column;
  gap: 12px;
}

.is-sidebar-collapsed .rail-brand {
  justify-content: center;
}

.is-sidebar-collapsed .rail-brand span,
.is-sidebar-collapsed .rail-link span {
  display: none;
}

.is-sidebar-collapsed .rail-collapse-toggle {
  flex-basis: 30px;
  width: 30px;
  height: 30px;
}

.is-sidebar-collapsed .rail-nav {
  justify-items: center;
}

.is-sidebar-collapsed .rail-link {
  justify-content: center;
  width: 48px;
  padding: 0;
}

.is-sidebar-collapsed .rail-link .el-icon {
  font-size: 21px;
}

.rail-link:hover {
  background: rgb(255 255 255 / 10%);
  color: #fff;
}

.rail-link.active {
  border-color: rgb(255 255 255 / 20%);
  background: rgb(255 255 255 / 15%);
  color: #fff;
  font-weight: 650;
}

.workspace-column {
  grid-column: 2;
  overflow: clip;
  min-width: 0;
  border-radius: 25px;
  background: #fff;
}

.layout-header {
  position: sticky;
  top: 0;
  z-index: 30;
  min-height: 72px;
  border-bottom: 1px solid var(--sky-border-soft);
  background: rgb(255 255 255 / 96%);
  backdrop-filter: blur(12px);
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  width: min(100% - 48px, var(--sky-content-width));
  min-height: 72px;
  margin-inline: auto;
}

.mobile-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--sky-text);
  text-decoration: none;
}

.mobile-brand strong {
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.header-shop-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--sky-text-sub);
  font-size: 14px;
  font-weight: 600;
}

.header-shop-state i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sky-text-weak);
}

.header-shop-state.is-open i {
  background: var(--sky-success);
}

.header-shop-state.is-closed i {
  background: var(--sky-danger);
}

.header-account {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 220px;
  min-height: 40px;
  padding: 0 11px;
  border-radius: var(--sky-radius);
  color: var(--sky-text-sub);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.header-account:hover {
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
}

.header-account span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout-main {
  width: 100%;
  padding: 24px 28px 48px;
}

.content-container {
  width: 100%;
  max-width: var(--sky-content-width);
  margin-inline: auto;
}

@media (max-width: 900px) {
  .main-layout {
    padding: 14px;
  }

  .workspace-shell {
    min-height: calc(100dvh - 28px);
    border-radius: 22px;
  }

  .side-rail {
    top: 28px;
    left: max(26px, calc((100vw - 1600px) / 2 + 26px));
    height: calc(100dvh - 56px);
    min-height: 0;
    padding: 16px 10px 12px;
    border-radius: 20px;
  }

  .header-content {
    width: calc(100% - 36px);
  }

  .layout-main {
    padding: 20px 18px 42px;
  }
}

@media (max-width: 600px) {
  .main-layout {
    padding: 0;
    padding-bottom: calc(68px + env(safe-area-inset-bottom));
  }

  .workspace-shell {
    display: block;
    min-height: 100dvh;
    overflow: visible;
    border: 0;
    border-radius: 0;
    box-shadow: none;
  }

  .workspace-column {
    overflow: visible;
    border-radius: 0;
  }

  .side-rail {
    position: fixed;
    top: auto;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 50;
    display: block;
    width: 100%;
    height: calc(62px + env(safe-area-inset-bottom));
    min-height: 0;
    border-radius: 0;
    padding: 0 6px env(safe-area-inset-bottom);
    border-top: 1px solid var(--sky-border-soft);
    border-right: 0;
    background: rgb(255 255 255 / 97%);
    color: var(--sky-text);
    box-shadow: 0 -6px 24px rgb(30 45 61 / 8%);
    backdrop-filter: blur(14px);
  }

  .rail-nav {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 0;
    height: 100%;
    padding: 4px 0 0;
  }

  .rail-brand {
    display: none;
  }

  .rail-collapse-toggle {
    display: none;
  }

  .rail-link {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 3px;
    width: auto;
    min-height: 0;
    height: 52px;
    padding: 0 2px;
    border: 0;
    border-radius: 9px;
    font-size: 10px;
    line-height: 1.2;
    color: var(--sky-text-weak);
  }

  .rail-link:hover {
    background: transparent;
    color: var(--sky-primary);
  }

  .rail-link.active {
    border-color: transparent;
    background: transparent;
    color: var(--sky-primary);
  }

  .rail-link span {
    display: block;
    overflow: hidden;
    max-width: 100%;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .main-layout.is-sidebar-collapsed .rail-link span {
    display: block;
  }

  .rail-link .el-icon {
    font-size: 19px;
  }

  .workspace-column {
    min-height: 100dvh;
  }

  .layout-header {
    min-height: 58px;
  }

  .header-content {
    width: calc(100% - 28px);
    min-height: 58px;
  }

  .mobile-brand img {
    width: 30px;
    height: 30px;
  }

  .mobile-brand strong {
    font-size: 15px;
  }

  .header-shop-state {
    margin-left: auto;
    font-size: 12px;
  }

  .header-account {
    width: 36px;
    justify-content: center;
    padding: 0;
  }

  .header-account span {
    display: none;
  }

  .layout-main {
    padding: 16px 12px 36px;
  }
}
</style>
