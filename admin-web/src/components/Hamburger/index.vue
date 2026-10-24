<template>
  <button
    class="hamburger-toggle"
    :class="{ 'is-active': isActive }"
    type="button"
    :aria-label="isActive ? '收起侧边栏' : '展开侧边栏'"
    :aria-expanded="isActive"
    @click="toggleClick"
  >
    <svg-icon name="hamburger" width="20" height="20" />
  </button>
</template>

<script lang="ts">
import { Component, Prop, Vue } from 'vue-facing-decorator'

@Component({
  'name': 'Hamburger'
})

export default class Hamburger extends Vue {
  @Prop({ 'default': false }) private isActive!: boolean

  private toggleClick() {
    this.$emit('toggleClick');
  }
}
</script>

<style lang="scss" scoped>
.hamburger-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.hamburger-toggle:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}

.svg-icon {
  vertical-align: middle;
}

.is-active {
  transform: rotate(180deg);
}
</style>
