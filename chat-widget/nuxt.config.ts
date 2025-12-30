// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },

  runtimeConfig: {
    public: {
      // 后端服务地址（所有 Coze API 请求都通过后端代理）
      chatServiceUrl: process.env.CHAT_SERVICE_URL || 'http://localhost:8081',

      // 以下配置已迁移到后端 application.properties，前端仅保留用于兼容
      cozeWorkflowId: process.env.COZE_WORKFLOW_ID || '',
      cozeAppId: process.env.COZE_APP_ID || '',
      cozeBotId: process.env.COZE_BOT_ID || '',
      cozeVoiceId: process.env.COZE_VOICE_ID || '',
      logServiceUrl: process.env.LOG_SERVICE_URL || 'http://localhost:8081/api/logs'
    }
  },

  ssr: false
})
