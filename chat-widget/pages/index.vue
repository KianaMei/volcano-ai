<script setup lang="ts">
const sessionName = ref('test-user-001')
const debugUrl = ref<string | null>(null)

// 组件事件方式
const onDebugUrl = (url: string) => {
  debugUrl.value = url
  console.log('debugUrl 已设置 (emit):', url)
}

// postMessage 方式（兼容 iframe 场景）
onMounted(() => {
  window.addEventListener('message', (event) => {
    if (event.data?.type === 'coze-debug-url' && event.data?.debugUrl) {
      debugUrl.value = event.data.debugUrl
      console.log('debugUrl 已设置 (postMessage):', event.data.debugUrl)
    }
  })
})

// 点击打开调试链接
const openDebugUrl = () => {
  if (debugUrl.value) {
    console.log('打开调试链接:', debugUrl.value)
    window.open(debugUrl.value, '_blank')
  }
}
</script>

<template>
  <div class="app-container">
    <div class="background-pattern"></div>
    
    <header class="top-bar">
      <div class="logo">
        <div class="logo-icon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" fill="currentColor" opacity="0.2"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <circle cx="9" cy="10" r="1.5" fill="currentColor"/>
            <circle cx="15" cy="10" r="1.5" fill="currentColor"/>
          </svg>
        </div>
        <span>Volcano Chat</span>
      </div>
      
      <div class="session-input">
        <label>Session ID</label>
        <input v-model="sessionName" type="text" placeholder="输入会话ID" />
      </div>

      <span class="debug-placeholder">{{ debugUrl ? '' : '发送消息后显示调试链接' }}</span>
    </header>

    <main class="main-content">
      <div class="chat-wrapper">
        <div class="chat-card">
          <ChatWidget :session-name="sessionName" :key="sessionName" @debug-url="onDebugUrl" />
        </div>
        <div v-if="debugUrl" class="debug-url">{{ debugUrl }}</div>
      </div>
    </main>

    <footer class="bottom-bar">
      <span>Powered by Coze AI</span>
    </footer>
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;600&display=swap');

.app-container {
  min-height: 100vh;
  font-family: 'Noto Sans SC', sans-serif;
  background: #faf7f2;
  color: #3d3d3d;
  position: relative;
  display: flex;
  flex-direction: column;
}

.background-pattern {
  position: fixed;
  inset: 0;
  background-image: 
    radial-gradient(circle at 20% 30%, rgba(229, 115, 100, 0.08) 0%, transparent 50%),
    radial-gradient(circle at 80% 70%, rgba(45, 149, 135, 0.08) 0%, transparent 50%);
  pointer-events: none;
}

.top-bar {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 14px 28px;
  background: rgba(255, 255, 255, 0.8);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  backdrop-filter: blur(12px);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  color: #2d2d2d;
}

.logo-icon {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e57364, #d4574a);
  border-radius: 10px;
  color: #fff;
  box-shadow: 0 2px 8px rgba(229, 115, 100, 0.3);
}

.session-input {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.session-input label {
  font-size: 12px;
  font-weight: 500;
  color: #888;
}

.session-input input {
  background: #fff;
  border: 1px solid #e0dcd5;
  border-radius: 8px;
  padding: 9px 14px;
  font-size: 13px;
  color: #333;
  font-family: 'SF Mono', 'Monaco', monospace;
  width: 160px;
  transition: all 0.2s;
}

.session-input input:focus {
  outline: none;
  border-color: #e57364;
  box-shadow: 0 0 0 3px rgba(229, 115, 100, 0.12);
}

.debug-link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  background: linear-gradient(135deg, #2d9587, #248f7a);
  border: none;
  border-radius: 8px;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(45, 149, 135, 0.25);
}

.debug-link:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(45, 149, 135, 0.35);
}

.debug-url {
  margin-top: 16px;
  padding: 12px 16px;
  font-size: 12px;
  color: #2d9587;
  font-family: 'SF Mono', 'Monaco', monospace;
  word-break: break-all;
  background: rgba(45, 149, 135, 0.08);
  border-radius: 8px;
  text-align: center;
}

.debug-placeholder {
  font-size: 12px;
  color: #bbb;
  font-style: italic;
}

.main-content {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.chat-wrapper {
  width: 100%;
  max-width: 520px;
}

.chat-card {
  background: #fff;
  border-radius: 20px;
  box-shadow: 
    0 4px 24px rgba(0, 0, 0, 0.06),
    0 1px 3px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.04);
  height: 680px;
  display: flex;
  flex-direction: column;
}

.chat-iframe {
  width: 100%;
  height: 580px;
  display: block;
}

.bottom-bar {
  position: relative;
  z-index: 10;
  text-align: center;
  padding: 14px;
  font-size: 12px;
  color: #aaa;
  background: rgba(255, 255, 255, 0.6);
  border-top: 1px solid rgba(0, 0, 0, 0.04);
}
</style>