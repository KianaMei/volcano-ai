// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },

  runtimeConfig: {
    public: {
      // TODO [生产环境] 配置后端服务地址
      // 通过环境变量 CHAT_SERVICE_URL 设置
      chatServiceUrl: process.env.CHAT_SERVICE_URL || 'http://localhost:8081',

      // TODO [生产环境] 必须修改！配置真实的业务方后端鉴权接口
      // 通过环境变量 NUXT_PUBLIC_CHAT_AUTH_URL 设置
      // 示例: https://api.your-business.com/chat/auth
      // 当前默认值指向 Mock 接口，仅用于开发测试
      chatAuthUrl: process.env.NUXT_PUBLIC_CHAT_AUTH_URL || 'http://localhost:8081/api/mock/website-a/init',

      // 以下配置已迁移到后端 application.properties，前端仅保留用于兼容
      cozeWorkflowId: process.env.COZE_WORKFLOW_ID || '',
      cozeAppId: process.env.COZE_APP_ID || '',
      cozeBotId: process.env.COZE_BOT_ID || '',
      cozeVoiceId: process.env.COZE_VOICE_ID || '',
      logServiceUrl: process.env.LOG_SERVICE_URL || 'http://localhost:8081/api/logs'
    }
  },

  // TODO [生产环境] 如需 SEO，考虑启用 SSR
  ssr: false
})
