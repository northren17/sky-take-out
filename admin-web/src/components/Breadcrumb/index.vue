<template>
  <el-breadcrumb
    class="app-breadcrumb"
    separator="/"
  >
    <transition-group name="breadcrumb">
      <el-breadcrumb-item
        v-for="(item, index) in breadcrumbs"
        :key="item.path"
      >
        <span
          v-if="item.redirect === 'noredirect' || index === breadcrumbs.length-1"
          class="no-redirect"
        >{{ item.meta.title }}</span>
        <a
          v-else
          @click.prevent="handleLink(item)"
        >{{ item.meta.title }}</a>
      </el-breadcrumb-item>
    </transition-group>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import pathToRegexp from 'path-to-regexp'
import { ref, watch } from 'vue'
import { useRoute, useRouter, type RouteRecordNormalized } from 'vue-router'

defineOptions({ name: 'AppBreadcrumb' })

type BreadcrumbRoute = RouteRecordNormalized & { redirect?: string }

const route = useRoute()
const router = useRouter()
const breadcrumbs = ref<BreadcrumbRoute[]>([])

watch(() => route.path, (path) => {
  if (path.startsWith('/redirect/')) {
    return
  }

  breadcrumbs.value = route.matched.filter(item => {
    return item.meta.title && item.meta.breadcrumb !== false
  }) as BreadcrumbRoute[]
}, { immediate: true })

function pathCompile(path: string) {
  const toPath = pathToRegexp.compile(path)
  return toPath(route.params)
}

function handleLink(item: BreadcrumbRoute) {
  if (item.redirect) {
    router.push(item.redirect)
    return
  }
  router.push(pathCompile(item.path))
}
</script>

<style lang="scss" scoped>
.el-breadcrumb__inner,
.el-breadcrumb__inner a {
  font-weight: 400 !important;
}

.app-breadcrumb.el-breadcrumb {
  display: inline-block;
  font-size: 14px;
  line-height: 50px;
  margin-left: 8px;

  .no-redirect {
    color: #97a8be;
    cursor: text;
  }
}
</style>
