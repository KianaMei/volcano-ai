<script setup lang="ts">
const route = useRoute()
const sessionName = computed(() => (route.query.session as string) || 'default-session')
</script>

<template>
  <div class="chat-page">
    <ChatWidget :session-name="sessionName" />
  </div>
</template>

<style scoped>
.chat-page {
  width: 100%;
  height: 100vh;
  margin: 0;
  padding: 0;
  overflow: hidden;
}

.chat-page :deep(.chat-widget) {
  width: 100%;
  height: 100%;
  max-height: none;
  border: none;
  border-radius: 0;
}
</style>
