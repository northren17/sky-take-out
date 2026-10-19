<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-facing-decorator'

@Component({
  name: 'App',
})
export default class App extends Vue {}
</script>
