<!--  -->
<template>
  <div class="empty-box">
    <div class="img-box">
      <img v-if="!isSearch"
           src="../../assets/table_empty.png"
           alt=""
      >
      <img v-else
           src="../../assets/search_table_empty.png"
      >
      <p>{{ !isSearch ? '这里空空如也~' : 'Sorry，木有找到您搜索的内容哦~' }}</p>
    </div>
  </div>
</template>

<script lang='ts'>
import { Vue, Component, Prop } from 'vue-facing-decorator'
@Component({
  name: 'Empty'
})
export default class Empty extends Vue {
  @Prop({ default: false }) isSearch: boolean //用来区分是搜索还是默认无数据
}
</script>
<style scoped lang="scss">
.empty-box {
  text-align: center;
  margin: 24px 0;
  img {
    display: block;
    margin: 0 auto 26px;
    width: 238px;
    height: 184px;
  }
  p {
    color: #818693;
  }
}
/* @import url(); 引入css类 */
</style>
