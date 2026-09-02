<template>
  <div class="page">
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="rule-name">工作台（待实现）</div>
      <div class="spacer"></div>
    </div>
    <div class="body">规则 ID：{{ id }}</div>
  </div>
</template>

<script setup>
defineProps({ id: { type: String, required: true } })
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.body { flex: 1; padding: 20px; }
</style>
