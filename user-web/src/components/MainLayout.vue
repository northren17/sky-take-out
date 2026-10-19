<script setup lang="ts">
import { computed, type Component } from 'vue'
import { ArrowRight, Dish, House, Location, Tickets, User } from '@element-plus/icons-vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { useCheckoutStore } from '@/stores/checkout'
import { useShopStore } from '@/stores/shop'
import { useUserStore } from '@/stores/user'
import VoiceAssistant from '@/components/VoiceAssistant.vue'

const route = useRoute()
const checkout = useCheckoutStore()
const shop = useShopStore()
const user = useUserStore()

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
    name: '个人中心',
    to: { path: '/my' },
    icon: User,
    active: () => route.path === '/my',
  },
]

const displayName = computed(
  () => user.baseUserInfo.nickName || user.account || '个人中心',
)

function onNavigate(item: NavItem) {
  if (item.name === '地址管理') checkout.setAddressBackPath('/index')
}
</script>

<template>
  <div class="main-layout">
    <div class="workspace-shell">
      <aside class="side-rail" aria-label="网站导航">
        <nav class="rail-nav" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.name"
            :to="item.to"
            class="rail-link"
            :class="{ active: item.active() }"
            :aria-current="item.active() ? 'page' : undefined"
            @click="onNavigate(item)"
          >
            <el-icon aria-hidden="true"><component :is="item.icon" /></el-icon>
            <span>{{ item.name }}</span>
          </RouterLink>
        </nav>

        <RouterLink class="rail-profile" to="/my" :aria-label="`个人中心：${displayName}`">
          <span class="profile-avatar" aria-hidden="true">{{ displayName.slice(0, 1) }}</span>
          <span class="profile-copy">
            <strong>{{ displayName }}</strong>
            <small>个人中心</small>
          </span>
          <el-icon class="profile-arrow" aria-hidden="true"><ArrowRight /></el-icon>
        </RouterLink>
      </aside>

      <div class="workspace-column">
        <header class="layout-header">
          <div class="header-content">
            <RouterLink class="mobile-brand" to="/index" aria-label="苍穹外卖首页">
              <img src="/static/logo.png" alt="" />
              <strong>苍穹外卖</strong>
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
                    ? '苍穹外卖'
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
    <VoiceAssistant v-if="user.token" />
  </div>
</template>

<style scoped>
.main-layout {
  --sky-sidebar-width: 232px;
  min-height: 100dvh;
  padding: 24px;
  background: #edf0f5;
}

.workspace-shell {
  display: grid;
  grid-template-columns: var(--sky-sidebar-width) minmax(0, 1fr);
  width: 100%;
  max-width: 1600px;
  min-height: calc(100dvh - 48px);
  margin-inline: auto;
  overflow: clip;
  border: 1px solid rgb(255 255 255 / 78%);
  border-radius: 26px;
  background: var(--sky-surface);
  box-shadow: 0 20px 60px rgb(71 100 137 / 12%);
}

.side-rail {
  position: sticky;
  top: 24px;
  display: flex;
  flex-direction: column;
  align-self: start;
  height: calc(100dvh - 48px);
  min-height: 620px;
  padding: 22px 16px 16px;
  border-right: 1px solid var(--sky-border-soft);
  background: #fff;
}

.mobile-brand img {
  width: 34px;
  height: 34px;
  object-fit: contain;
}

.rail-nav {
  display: grid;
  gap: 6px;
  padding-top: 18px;
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
  color: var(--sky-text-sub);
  font-size: 14px;
  font-weight: 550;
  text-decoration: none;
  transition: color 160ms ease, background 160ms ease, border-color 160ms ease;
}

.rail-link .el-icon {
  flex: 0 0 auto;
  font-size: 19px;
}

.rail-link:hover {
  background: var(--sky-surface-2);
  color: var(--sky-text);
}

.rail-link.active {
  border-color: #f6d7ad;
  background: #fff7eb;
  color: var(--sky-primary);
  font-weight: 650;
}

.rail-profile {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  min-height: 68px;
  padding: 12px 8px 0;
  margin-top: auto;
  border-top: 1px solid var(--sky-border-soft);
  color: var(--sky-text);
  text-decoration: none;
}

.profile-avatar {
  display: grid;
  flex: 0 0 38px;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--sky-border-soft);
  border-radius: 50%;
  background: var(--sky-primary-soft);
  color: var(--sky-primary);
  font-size: 15px;
  font-weight: 700;
}

.profile-copy {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 2px;
}

.profile-copy strong,
.profile-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-copy strong {
  font-size: 13px;
}

.profile-copy small {
  color: var(--sky-text-weak);
  font-size: 11px;
}

.profile-arrow {
  color: var(--sky-text-weak);
}

.workspace-column {
  min-width: 0;
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
    --sky-sidebar-width: 76px;
    padding: 14px;
  }

  .workspace-shell {
    min-height: calc(100dvh - 28px);
    border-radius: 22px;
  }

  .side-rail {
    top: 14px;
    height: calc(100dvh - 28px);
    min-height: 520px;
    padding: 18px 10px 12px;
  }

  .rail-link span,
  .profile-copy,
  .profile-arrow {
    display: none;
  }

  .rail-nav {
    justify-items: center;
  }

  .rail-link {
    justify-content: center;
    width: 48px;
    padding: 0;
  }

  .rail-link .el-icon {
    font-size: 21px;
  }

  .rail-profile {
    justify-content: center;
    padding-inline: 0;
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
    padding: 0 6px env(safe-area-inset-bottom);
    border-top: 1px solid var(--sky-border-soft);
    border-right: 0;
    background: rgb(255 255 255 / 97%);
    box-shadow: 0 -6px 24px rgb(30 45 61 / 8%);
    backdrop-filter: blur(14px);
  }

  .rail-profile {
    display: none;
  }

  .rail-nav {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 0;
    height: 100%;
    padding: 4px 0 0;
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
  }

  .rail-link span {
    display: block;
    overflow: hidden;
    max-width: 100%;
    text-overflow: ellipsis;
    white-space: nowrap;
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
