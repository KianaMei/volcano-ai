<script setup lang="ts">
import { nextTick, watch, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{
  sessionName: string
}>()

const emit = defineEmits<{
  (e: 'debugUrl', url: string): void
}>()

const {
  messages,
  conversations,
  isLoading,
  isCreatingConversation,
  isLoadingConversations,
  conversationId,
  error,
  debugUrl,
  isSpeaking,
  speakingMessageId,
  getToken,
  sendMessage,
  stopRequest,
  clearMessages,
  createNewConversation,
  fetchConversationList,
  switchConversation,
  copyText,
  speakText,
  stopSpeaking
} = useCozeChat(props.sessionName)
const { isRecording, isTranscribing, error: voiceError, recognizedText, startRecording, stopRecording, cancelRecording } = useVoiceInput({ getToken })

const inputContent = ref('')
const messageListRef = ref<HTMLElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const expandBtnRef = ref<HTMLButtonElement | null>(null)
const showHistoryPanel = ref(false)
const thinkingSeconds = ref(0)
const finalThinkingTime = ref<number | null>(null)
const copiedMessageId = ref<string | null>(null)
const isInputExpanded = ref(false)
const showExpandBtn = ref(false)
let thinkingTimer: ReturnType<typeof setInterval> | null = null

// 检测是否需要显示扩展按钮
const checkExpandBtn = () => {
  if (!textareaRef.value) return
  const maxCollapsed = 56
  showExpandBtn.value = textareaRef.value.scrollHeight > maxCollapsed
}

const toggleExpand = () => {
  isInputExpanded.value = !isInputExpanded.value
  nextTick(() => {
    if (textareaRef.value && isInputExpanded.value) {
      // 获取聊天窗口高度，计算展开高度
      const chatWidget = textareaRef.value.closest('.chat-widget')
      if (chatWidget) {
        const widgetHeight = chatWidget.clientHeight
        const expandedHeight = widgetHeight - 130
        const currentHeight = textareaRef.value.offsetHeight
        const extraHeight = expandedHeight - currentHeight
        textareaRef.value.style.height = expandedHeight + 'px'
        textareaRef.value.style.marginTop = -extraHeight + 'px'
        // 按钮跟着到顶部
        if (expandBtnRef.value) {
          expandBtnRef.value.style.bottom = 'auto'
          expandBtnRef.value.style.top = -extraHeight - 26 + 'px'
        }
      }
    } else if (textareaRef.value) {
      textareaRef.value.style.height = ''
      textareaRef.value.style.marginTop = ''
      if (expandBtnRef.value) {
        expandBtnRef.value.style.bottom = ''
        expandBtnRef.value.style.top = ''
      }
    }
  })
}

const startThinkingTimer = () => {
  thinkingSeconds.value = 0
  finalThinkingTime.value = null
  thinkingTimer = setInterval(() => {
    thinkingSeconds.value++
  }, 1000)
}

const stopThinkingTimer = () => {
  if (thinkingTimer) {
    clearInterval(thinkingTimer)
    thinkingTimer = null
    finalThinkingTime.value = thinkingSeconds.value
  }
}

marked.setOptions({
  breaks: true,
  gfm: true
})

const renderMarkdown = (content: string) => {
  if (!content) return ''
  const html = marked.parse(content)
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}

const isThinking = computed(() => {
  if (!isLoading.value) return false
  const lastMsg = messages.value[messages.value.length - 1]
  return lastMsg && lastMsg.role === 'assistant' && lastMsg.content === ''
})

watch(debugUrl, (url) => {
  if (url) {
    emit('debugUrl', url)
  }
})

// 监听加载状态，停止计时器
watch(isLoading, (loading) => {
  if (!loading && thinkingTimer) {
    stopThinkingTimer()
  }
})

const handleStop = () => {
  stopRequest()
  stopThinkingTimer()
}

const handleCopy = async (text: string, messageId: string) => {
  const success = await copyText(text)
  if (success) {
    copiedMessageId.value = messageId
    setTimeout(() => {
      copiedMessageId.value = null
    }, 2000)
  }
}

const handleSpeak = (text: string, messageId: string) => {
  if (speakingMessageId.value === messageId) {
    stopSpeaking()
  } else {
    speakText(text, messageId)
  }
}

const handleSend = async () => {
  if (!inputContent.value.trim() || isLoading.value) return
  const content = inputContent.value
  inputContent.value = ''
  startThinkingTimer()
  await sendMessage(content)
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

const handleVoiceStart = async () => {
  try {
    await startRecording()
  } catch (e) {
    console.error('录音启动失败:', e)
  }
}

const handleVoiceStop = async () => {
  if (!isRecording.value && !isTranscribing.value) return
  try {
    const text = await stopRecording()
    if (text.trim()) {
      startThinkingTimer()
      await sendMessage(text)
    }
  } catch (e) {
    console.error('转录失败:', e)
  }
}

const handleVoiceCancel = () => {
  cancelRecording()
}

const handleNewConversation = async () => {
  if (isLoading.value || isCreatingConversation.value) return
  await createNewConversation()
}

const clearScreen = () => {
  // 只清空屏幕上的消息，不改变 conversationId
  messages.value = []
}

const handleOpenHistory = async () => {
  showHistoryPanel.value = true
  await fetchConversationList()
}

const handleSelectConversation = async (convId: string) => {
  await switchConversation(convId)
  showHistoryPanel.value = false
}

const formatTime = (timestamp: number) => {
  const date = new Date(timestamp)
  const now = new Date()
  const diff = now.getTime() - timestamp
  
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}/${date.getDate()}`
  }
  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`
}

watch(messages, async () => {
  await nextTick()
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}, { deep: true })

watch(inputContent, async () => {
  await nextTick()
  checkExpandBtn()
})
</script>

<template>
  <div class="chat-widget">
    <div class="chat-header">
      <div class="header-left">
        <div class="bot-avatar">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" fill="currentColor" opacity="0.2"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <circle cx="9" cy="10" r="1.5" fill="currentColor"/>
            <circle cx="15" cy="10" r="1.5" fill="currentColor"/>
          </svg>
        </div>
        <div class="header-info">
          <span class="title">AI 助手</span>
        </div>
      </div>
      <div class="header-actions">
        <button 
          class="history-btn" 
          @click="handleOpenHistory" 
          :disabled="isLoadingConversations"
          title="历史对话"
        >
          <svg v-if="!isLoadingConversations" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.5"/>
            <path d="M12 6v6l4 2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
          <span v-else class="mini-loader"></span>
        </button>
        <button
          class="new-chat-btn"
          @click="handleNewConversation"
          :disabled="isLoading || isCreatingConversation"
          title="新建对话"
        >
          <svg v-if="!isCreatingConversation" width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <span v-else class="mini-loader"></span>
        </button>
        <button class="clear-btn" @click="clearScreen" title="清屏">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <!-- 扫把柄 -->
            <line x1="18" y1="3" x2="9" y2="12" stroke-linecap="round"/>
            <!-- 扫把头 -->
            <path d="M9 12L6 15M9 12L8 16M9 12L12 14" stroke-linecap="round"/>
            <path d="M6 15L4 19M8 16L7 20M12 14L13 18" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- 历史对话面板 -->
    <div v-if="showHistoryPanel" class="history-panel">
      <div class="history-header">
        <span class="history-title">历史对话</span>
        <button class="history-close" @click="showHistoryPanel = false">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
      <div class="history-list">
        <div v-if="isLoadingConversations" class="history-loading">
          <span class="mini-loader"></span>
          <span>加载中...</span>
        </div>
        <div v-else-if="conversations.length === 0" class="history-empty">
          暂无历史对话
        </div>
        <div
          v-else
          v-for="conv in conversations"
          :key="conv.id"
          :class="['history-item', { active: conv.id === conversationId }]"
          @click="handleSelectConversation(conv.id)"
        >
          <div class="history-item-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="history-item-content">
            <span class="history-item-name">{{ conv.name }}</span>
            <span class="history-item-time">{{ formatTime(conv.updated_at) }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-if="showHistoryPanel" class="history-backdrop" @click="showHistoryPanel = false"></div>

    <div ref="messageListRef" class="message-list">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-illustration">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none">
            <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <p class="empty-text">有什么可以帮您？</p>
        <p class="empty-hint">输入消息开始对话</p>
      </div>

      <div
        v-for="msg in messages"
        :key="msg.id"
        :class="['message-row', msg.role]"
      >
        <template v-if="msg.role === 'user' || msg.content">
          <div class="avatar">
            <template v-if="msg.role === 'user'">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="2"/>
                <path d="M4 20c0-4 4-6 8-6s8 2 8 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </template>
            <template v-else>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" fill="currentColor" opacity="0.3"/>
                <path d="M8 14s1.5 2 4 2 4-2 4-2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </template>
          </div>
          <div class="message-content">
            <div class="bubble">
              <div v-if="msg.role === 'assistant'" class="markdown-content" v-html="renderMarkdown(msg.content)"></div>
              <template v-else>{{ msg.content }}</template>
              <span v-if="msg.isStreaming && msg.content" class="typing-cursor"></span>
            </div>
            <div v-if="msg.content && !msg.isStreaming" class="message-actions">
              <button
                class="action-btn"
                :class="{ copied: copiedMessageId === msg.id }"
                @click="handleCopy(msg.content, msg.id)"
                :title="copiedMessageId === msg.id ? '已复制' : '复制'"
              >
                <svg v-if="copiedMessageId !== msg.id" width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/>
                  <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" stroke="currentColor" stroke-width="2"/>
                </svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M20 6L9 17l-5-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <button
                class="action-btn"
                :class="{ speaking: speakingMessageId === msg.id }"
                @click="handleSpeak(msg.content, msg.id)"
                :title="speakingMessageId === msg.id ? '停止朗读' : '朗读'"
              >
                <svg v-if="speakingMessageId !== msg.id" width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M11 5L6 9H2v6h4l5 4V5z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M15.54 8.46a5 5 0 010 7.07M19.07 4.93a10 10 0 010 14.14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                </svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/>
                </svg>
              </button>
            </div>
          </div>
        </template>
      </div>

      <div v-if="isThinking" class="thinking-indicator">
        <div class="thinking-avatar">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" fill="currentColor" opacity="0.3"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </div>
        <div class="thinking-bubble">
          <span class="thinking-text">思考中</span>
          <span class="thinking-dots">
            <span></span><span></span><span></span>
          </span>
          <span class="thinking-time">{{ thinkingSeconds }}秒</span>
          <button class="stop-btn" @click="handleStop" title="停止">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
              <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/>
            </svg>
          </button>
        </div>
      </div>

      <div v-else-if="finalThinkingTime !== null && messages.length > 0" class="thinking-done">
        <span class="thinking-done-text">已思考 {{ finalThinkingTime }} 秒</span>
      </div>

      <div v-if="isLoading && messages.length === 0" class="loading-indicator">
        <div class="dot-loader">
          <span></span><span></span><span></span>
        </div>
      </div>

      <div v-if="error" class="error-msg">{{ error }}</div>
    </div>

    <div class="input-area">
      <!-- 录音中：显示取消按钮 -->
      <button
        v-if="isRecording || isTranscribing"
        class="cancel-btn"
        @click="handleVoiceCancel"
        title="取消录音"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </button>
      <!-- 空闲时：显示麦克风按钮 -->
      <button
        v-else
        class="mic-btn"
        :disabled="isLoading"
        @click="handleVoiceStart"
        title="语音输入"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M12 1a4 4 0 00-4 4v7a4 4 0 008 0V5a4 4 0 00-4-4z" stroke="currentColor" stroke-width="2"/>
          <path d="M19 10v2a7 7 0 01-14 0v-2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          <path d="M12 19v4m-4 0h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </button>

      <div :class="['text-input-wrap', { expanded: isInputExpanded, recording: isRecording || isTranscribing }]">
        <button
          v-if="showExpandBtn && !isRecording && !isTranscribing"
          ref="expandBtnRef"
          class="expand-btn"
          @click="toggleExpand"
          :title="isInputExpanded ? '收起' : '展开'"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
            <path v-if="!isInputExpanded" d="M18 15l-6-6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path v-else d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <!-- 录音时显示实时识别文字 -->
        <div v-if="isRecording || isTranscribing" class="voice-status">
          <span v-if="isRecording && !recognizedText" class="voice-hint">
            <span class="rec-dot"></span>
            正在聆听...
          </span>
          <span v-else-if="recognizedText" class="voice-text">{{ recognizedText }}</span>
          <span v-else class="voice-hint">
            <span class="mini-loader"></span>
            识别中...
          </span>
        </div>
        <textarea
          v-else
          ref="textareaRef"
          v-model="inputContent"
          placeholder="输入消息..."
          rows="1"
          :disabled="isLoading"
          @keydown="handleKeydown"
        />
      </div>

      <!-- 录音中：显示完成按钮 -->
      <button
        v-if="isRecording || isTranscribing"
        class="send-btn recording"
        :disabled="isTranscribing"
        @click="handleVoiceStop"
        title="完成录音"
      >
        <svg v-if="!isTranscribing" width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M20 6L9 17l-5-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span v-else class="mini-loader light"></span>
      </button>
      <!-- 空闲时：显示发送按钮 -->
      <button
        v-else
        class="send-btn"
        :disabled="!inputContent.trim() || isLoading"
        @click="handleSend"
      >
        <svg v-if="!isLoading" width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span v-else class="mini-loader light"></span>
      </button>
    </div>

    <div v-if="voiceError" class="voice-toast">{{ voiceError }}</div>
  </div>
</template>

<style>
@import '~/assets/css/chat-widget.css';
</style>
