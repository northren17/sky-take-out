import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import Breadcrumb from '@/components/Breadcrumb/index.vue'

const EmptyRoute = defineComponent({
  render: () => h('div')
})

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    children: [{
      path: 'dashboard',
      component: EmptyRoute,
      meta: { title: 'dashboard' }
    }]
  },
  {
    path: '/menu',
    component: EmptyRoute,
    meta: { title: 'menu' },
    children: [{
      path: 'menu1',
      component: EmptyRoute,
      meta: { title: 'menu1' },
      children: [{
        path: 'menu1-1',
          component: EmptyRoute,
          meta: { title: 'menu1-1' }
      }, {
        path: 'menu1-2',
        component: EmptyRoute,
        meta: { title: 'menu1-2' },
        children: [{
          path: 'menu1-2-1',
          component: EmptyRoute,
          meta: { title: 'menu1-2-1' }
        }, {
          path: 'menu1-2-2',
          component: EmptyRoute
        }]
      }]
    }]
  }
]

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(Breadcrumb, {
    global: {
      plugins: [router, ElementPlus]
    }
  })
  await flushPromises()
  return { wrapper, router }
}

describe('Breadcrumb.vue', () => {
  it('renders a single dashboard breadcrumb', async () => {
    const { wrapper } = await mountAt('/dashboard')
    expect(wrapper.findAll('.el-breadcrumb__inner')).toHaveLength(1)
  })

  it('renders parent and current route breadcrumbs', async () => {
    const { wrapper } = await mountAt('/menu/menu1')
    expect(wrapper.findAll('.el-breadcrumb__inner')).toHaveLength(2)
  })

  it('renders nested route breadcrumbs', async () => {
    const { wrapper } = await mountAt('/menu/menu1/menu1-2/menu1-2-1')
    expect(wrapper.findAll('.el-breadcrumb__inner')).toHaveLength(4)
  })

  it('omits breadcrumbs without a title', async () => {
    const { wrapper } = await mountAt('/menu/menu1/menu1-2/menu1-2-2')
    expect(wrapper.findAll('.el-breadcrumb__inner')).toHaveLength(3)
  })

  it('renders parent breadcrumb links', async () => {
    const { wrapper } = await mountAt('/menu/menu1/menu1-2/menu1-2-2')
    expect(wrapper.findAll('.el-breadcrumb__inner').at(1)?.find('a').text()).toBe('menu1')
  })

  it('does not link the current breadcrumb', async () => {
    const { wrapper } = await mountAt('/menu/menu1/menu1-2/menu1-2-1')
    expect(wrapper.findAll('.el-breadcrumb__inner').at(3)?.find('a').exists()).toBe(false)
  })
})
