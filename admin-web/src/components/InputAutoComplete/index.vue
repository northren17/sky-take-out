<!--  -->
<template>
  <div class="input-auto-complete">
    <el-input
      v-model="input"
      :placeholder="placeholder"
      style="width: 250px"
      clearable
      @clear="init"
      @keyup.enter="init"
    >
      <template #prefix>
        <el-icon class="el-input__icon" style="cursor: pointer" @click="init">
          <Search />
        </el-icon>
      </template>
    </el-input>
  </div>
</template>

<script lang='ts'>
import { Vue, Component, Prop } from 'vue-facing-decorator'
@Component({
  name: 'InputAutoComplete',
})
export default class InputAutoComplete extends Vue {
  private input: any = ''
  @Prop({ default: [] }) data: Array<any>
  @Prop({ default: '' }) placeholder: string
  @Prop({ default: 'name' }) ObKey: string

  init() {
    this.$emit('init', this.input)
  }
}
</script>
<style scoped>
.input-auto-complete {
  display: inline-block;
}
</style>
