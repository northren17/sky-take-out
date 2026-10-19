<template>
  <main id="admin-main-content" class="app-main" tabindex="-1">
    <transition
      name="fade-transform"
      mode="out-in"
    >
      <router-view />
    </transition>
  </main>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-facing-decorator'

@Component({
  'name': 'AppMain'
})
export default class AppMain extends Vue {}
</script>

<style lang="scss" scoped>
.app-main {
  height: calc(100% - 64px);
  overflow-y: auto;
}
</style>
