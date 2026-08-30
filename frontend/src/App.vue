<template>
  <div class="bg-fx"></div>
  <ToastHost />
  <router-view />
</template>

<script setup lang="ts">
import ToastHost from './components/ui/ToastHost.vue'
</script>
