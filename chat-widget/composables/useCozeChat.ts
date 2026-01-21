import { ref } from 'vue'

// 消息类型
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  isStreaming?: boolean
}

// 会话类型
export interface Conversation {
  id: string
  name: string
  created_at: number
  updated_at: number
}

// SSE 事件类型 - 收紧定义
type SSEEventType =
  | 'conversation.chat.created'
  | 'conversation.message.delta'
  | 'conversation.message.completed'
  | 'conversation.chat.completed'
  | 'conversation.chat.failed'
  | 'done'
  | 'error'

interface SSEChatCreatedData {
  conversation_id: string
  chat_id: string
  debug_url?: string
}

interface SSEMessageDeltaData {
  type: 'answer' | 'function_call' | 'tool_output'
  content: string
  role?: string
}

interface SSEChatFailedData {
  status: string
  code?: number
  msg?: string
}

interface SSEDoneData {
  debug_url?: string
}

// 类型守卫
function isChatCreatedData(data: unknown): data is SSEChatCreatedData {
  return typeof data === 'object' && data !== null && 'conversation_id' in data && 'chat_id' in data
}

function isMessageDeltaData(data: unknown): data is SSEMessageDeltaData {
  return typeof data === 'object' && data !== null && 'type' in data && 'content' in data
}

function isChatFailedData(data: unknown): data is SSEChatFailedData {
  return typeof data === 'object' && data !== null && 'status' in data
}

// Coze API 消息类型
interface CozeMessage {
  id: string
  conversation_id: string
  role: 'user' | 'assistant'
  content: string
  content_type: string
  type: string
  created_at: number
}

// 生成存储 key
const getStorageKey = (sessionName: string) => `coze_conversation_${sessionName}`

export function useCozeChat(sessionName: string) {
  const config = useRuntimeConfig()

  // 状态
  const messages = ref<ChatMessage[]>([])
  const conversations = ref<Conversation[]>([])
  const isLoading = ref(false)
  const isLoadingHistory = ref(false)
  const isCreatingConversation = ref(false)
  const isLoadingConversations = ref(false)
  const conversationId = ref<string | null>(null)
  const error = ref<string | null>(null)
  const debugUrl = ref<string | null>(null)
  const currentChatId = ref<string | null>(null)

  // 用于中止请求
  let abortController: AbortController | null = null

  // TTS 播放状态
  const isSpeaking = ref(false)
  const speakingMessageId = ref<string | null>(null)
  type TtsAudioState = {
    ws: WebSocket
    getAudioContext: () => AudioContext | null
    getCurrentSource: () => AudioBufferSourceNode | null
    getAudioQueue: () => AudioBuffer[]
    setStopped: (val: boolean) => void
  }
  let currentAudio: TtsAudioState | null = null

  // 状态变量 - Token 相关
  const chatToken = ref<string>('')
  const tokenExpiresAt = ref<number>(0)
  const isInitialized = ref(false)
  const isRefreshing = ref(false) // 刷新锁
  let renewalTimer: ReturnType<typeof setTimeout> | null = null

  // 获取后端服务地址
  const getBackendUrl = (): string => {
    return (config.public.chatServiceUrl as string) || 'http://localhost:8081'
  }

  // 获取鉴权接口地址 (产业互联网后端)
  // TODO [生产环境] 将 chatAuthUrl 配置为真实的业务方后端地址
  // 默认值指向 Mock 接口，仅用于开发测试
  // 生产环境示例: https://your-business-backend.com/api/chat/init
  const getAuthUrl = (): string => {
    return (config.public.chatAuthUrl as string) || 'http://localhost:8081/api/mock/website-a/init'
  }

  // 初始化/刷新 Token
  // 该接口需符合《产业互联网后端接口约定文档》
  const initChatSession = async (force = false): Promise<string> => {
    if (chatToken.value && !force && Date.now() < tokenExpiresAt.value) {
      return chatToken.value
    }

    if (isRefreshing.value) {
      // 如果正在刷新，轮询等待结果
      return new Promise((resolve, reject) => {
        const check = setInterval(() => {
          if (!isRefreshing.value) {
            clearInterval(check)
            if (chatToken.value) resolve(chatToken.value)
            else reject(new Error('Token refresh failed'))
          }
        }, 100)
      })
    }

    try {
      isRefreshing.value = true
      const authUrl = getAuthUrl()
      console.log('[Chat] Requesting auth token from:', authUrl)
      
      // 调用产业互联网后端接口
      // 注意：此处会自动携带浏览器 Cookie
      const response = await fetch(authUrl, {
        method: 'POST',
      })

      if (!response.ok) {
        throw new Error(`Auth failed: ${response.status}`)
      }

      const res = await response.json()
      // 适配约定文档的响应结构: { data: { token: "...", expires_in: 7200 } }
      // 兼容旧 Mock 结构 (Mock 直接返回平铺对象或 data)
      const data = res.data || res 
      
      const newToken = data.token
      const expiresInSeconds = data.expires_in || 7200 // 默认 2 小时

      if (!newToken) throw new Error('Invalid token response')

      chatToken.value = newToken
      // 设置本地过期时间 (留 10 秒缓冲)
      tokenExpiresAt.value = Date.now() + (expiresInSeconds * 1000) - 10000
      isInitialized.value = true
      
      console.log('[Chat] Token updated. Expires in:', expiresInSeconds, 's')

      // 安排下一次静默刷新 (提前 5 分钟)
      scheduleTokenRenewal(expiresInSeconds)

      return newToken
    } catch (e) {
      console.error('[Chat] Failed to init/refresh session:', e)
      error.value = '连接已断开，请刷新页面重试'
      chatToken.value = '' // 清空无效 Token
      throw e
    } finally {
      isRefreshing.value = false
    }
  }

  // 安排静默刷新
  const scheduleTokenRenewal = (expiresInSeconds: number) => {
    if (renewalTimer) {
      clearTimeout(renewalTimer)
      renewalTimer = null
    }

    // 提前 5 分钟刷新
    const refreshDelay = Math.max((expiresInSeconds - 300) * 1000, 0)
    
    if (refreshDelay > 0) {
      console.log('[Chat] Next silent refresh in', Math.round(refreshDelay / 60000), 'minutes')
      renewalTimer = setTimeout(async () => {
        console.log('[Chat] Triggering silent refresh...')
        try {
          await initChatSession(true)
        } catch (e) {
          console.warn('[Chat] Silent refresh failed, will retry on next user action')
        }
      }, refreshDelay)
    }
  }

  // 获取 Token (带自动初始化)
  const getToken = async (): Promise<string> => {
    if (!chatToken.value || Date.now() > tokenExpiresAt.value) {
      return await initChatSession(true)
    }
    return chatToken.value
  }

  // 监听组件挂载，自动初始化
  onMounted(() => {
    initChatSession()
  })

  // 获取对话列表
  const fetchConversationList = async () => {
    // 暂不实现列表获取，因为 S2S 模式下暂未开放此接口，且当前 UI 似乎不需要
    // 如需实现，需在后端增加对应代理接口
    console.warn('[Chat] fetchConversationList not implemented for S2S mode yet.')
  }

  // 切换对话
  const switchConversation = async (convId: string) => {
    if (convId === conversationId.value) return

    conversationId.value = convId
    saveConversationId(convId)
    messages.value = []
    await fetchMessageHistory(convId)
  }

  // 封装带 401 重试的 fetch
  const authorizedFetch = async (url: string, options: RequestInit = {}): Promise<Response> => {
    let token = await getToken()
    
    const headers = new Headers(options.headers)
    headers.set('X-Chat-Token', token)
    
    let response = await fetch(url, { ...options, headers })

    // 拦截 401：Token 失效
    if (response.status === 401) {
      console.warn('[Chat] 401 Unauthorized detected. Retrying with fresh token...')
      try {
        // 强制刷新 Token
        token = await initChatSession(true)
        // 重试请求
        headers.set('X-Chat-Token', token)
        response = await fetch(url, { ...options, headers })
      } catch (e) {
        console.error('[Chat] Re-auth failed', e)
        throw e
      }
    }
    
    return response
  }

  // 获取消息历史
  const fetchMessageHistory = async (convId?: string) => {
    const targetId = convId || conversationId.value
    if (!targetId) return

    try {
      isLoadingHistory.value = true
      const backendUrl = getBackendUrl()
      
      // 使用带重试的 fetch
      const response = await authorizedFetch(`${backendUrl}/api/chat/messages/${targetId}`, {
        method: 'GET'
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const result = await response.json()
      if (result.code === 0 && result.data) {
        messages.value = result.data
          .filter((m: CozeMessage) => m.type === 'question' || m.type === 'answer')
          .map((m: CozeMessage) => ({
            id: m.id,
            role: m.role,
            content: m.content,
            timestamp: m.created_at * 1000,
            isStreaming: false
          }))
          // 简单去重
          .filter((v: ChatMessage, i: number, a: ChatMessage[]) => a.findIndex(t => t.id === v.id) === i)
          // 按时间排序
          .sort((a: ChatMessage, b: ChatMessage) => a.timestamp - b.timestamp)
      }
    } catch (e) {
      console.error('[Chat] Fetch history failed:', e)
      error.value = '获取历史消息失败'
    } finally {
      isLoadingHistory.value = false
    }
  }

  // 创建新会话
  const createNewConversation = async () => {
    try {
      isCreatingConversation.value = true
      error.value = null

      const backendUrl = getBackendUrl()
      const response = await authorizedFetch(`${backendUrl}/api/chat/conversation/create`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({})
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const result = await response.json()
      if (result.code === 0 && result.data?.id) {
        messages.value = []
        conversationId.value = result.data.id
        saveConversationId(result.data.id)
        return result.data.id
      } else {
        throw new Error(result.msg || 'Create conversation failed')
      }
    } catch (e) {
      console.error('[Chat] Create conversation failed:', e)
      error.value = '创建会话失败'
      return null
    } finally {
      isCreatingConversation.value = false
    }
  }

  const cancelChat = async (convId: string, chatId: string) => {
    try {
      const backendUrl = getBackendUrl()
      await authorizedFetch(`${backendUrl}/api/chat/cancel`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          conversationId: convId,
          chatId: chatId
        })
      })
    } catch (e) {
      console.warn('[Chat] Cancel chat failed:', e)
    }
  }

  // 停止请求
  const stopRequest = () => {
    const convId = conversationId.value
    const chatId = currentChatId.value
    if (convId && chatId) void cancelChat(convId, chatId)
    if (abortController) {
      abortController.abort()
      abortController = null
    }
  }

  // 清空对话
  const clearMessages = () => {
    messages.value = []
    conversationId.value = null
    clearStoredConversation()
    error.value = null
  }

  // 统一处理 debug_url（消除重复）
  const handleDebugUrl = (url: string) => {
    debugUrl.value = url
    // 安全的 postMessage：只发给同源父窗口，如果是跨域 iframe 则需要配置具体域名
    const targetOrigin = window.location.origin
    window.parent.postMessage({ type: 'coze-debug-url', debugUrl: url }, targetOrigin)
  }

  // SSE 事件处理器（从 sendMessage 拆分出来）
  const processSSEEvent = (event: SSEEventType, data: unknown) => {
    console.log('[SSE Debug]', event, data) // Debug logging
    switch (event) {
      case 'conversation.chat.created':
        if (isChatCreatedData(data)) {
          conversationId.value = data.conversation_id
          saveConversationId(data.conversation_id)
          currentChatId.value = data.chat_id
          if (data.debug_url) handleDebugUrl(data.debug_url)
        }
        break

      case 'conversation.message.delta':
        if (isMessageDeltaData(data) && data.type === 'answer') {
          const lastMsg = messages.value[messages.value.length - 1]
          if (lastMsg?.role === 'assistant') {
            lastMsg.content += data.content
            messages.value = [...messages.value]
          }
        }
        break

      case 'conversation.chat.completed': {
        const lastMsg = messages.value[messages.value.length - 1]
        if (lastMsg) lastMsg.isStreaming = false
        currentChatId.value = null
        break
      }

      case 'conversation.chat.failed':
        if (isChatFailedData(data)) {
          error.value = data.msg || data.status || '对话失败'
        }
        currentChatId.value = null
        break

      case 'done': {
        const doneData = data as SSEDoneData
        if (doneData.debug_url) handleDebugUrl(doneData.debug_url)
        currentChatId.value = null
        break
      }

      case 'error': {
        const errData = data as any
        console.error('[Coze] SSE Error:', errData)
        if (errData.code === 4002) {
          // 4002: Conversation does not exist or permission denied
          // 这通常发生在 Token 变更后（如从 PAT 换成 OAuth），但前端还缓存了旧 Token 创建的 Conversation ID
          console.warn('[Coze] Detected invalid conversation ID (4002). Clearing local cache.')
          conversationId.value = null
          clearStoredConversation()
          error.value = '会话已过期或不兼容，请重试以开始新会话。'
        } else {
          error.value = errData.msg || '发生错误'
        }
        currentChatId.value = null
        break
      }
    }
  }

  // 发送消息
  const sendMessage = async (content: string, params: Record<string, any> = {}) => {
    if (!content.trim() || isLoading.value) return

    if (!chatToken.value) await initChatSession()

    error.value = null
    isLoading.value = true
    debugUrl.value = null
    currentChatId.value = null

    abortController = new AbortController()

    // 1. 添加用户消息
    messages.value.push({
      id: `user-${Date.now()}`,
      role: 'user',
      content: content.trim(),
      timestamp: Date.now()
    })

    // 2. 添加助手消息占位
    const assistantMsg = {
      id: `assistant-${Date.now()}`,
      role: 'assistant' as const,
      content: '',
      timestamp: Date.now(),
      isStreaming: true
    }
    messages.value.push(assistantMsg)

    try {
      const backendUrl = getBackendUrl()
      const response = await fetch(`${backendUrl}/api/chat/send`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Chat-Token': chatToken.value
        },
        body: JSON.stringify({
          message: content.trim(),
          conversationId: conversationId.value,
          params: params
        }),
        signal: abortController.signal
      })

      if (!response.ok) {
        throw new Error(`Backend error: ${response.status}`)
      }

      if (!response.body) return

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent: SSEEventType | '' = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            const eventName = line.slice(6).trim()
            // 简单兼容处理
            if (eventName === 'conversation.message.delta' ||
              eventName === 'conversation.chat.created' ||
              eventName === 'conversation.chat.completed' ||
              eventName === 'conversation.chat.failed' ||
              eventName === 'done' ||
              eventName === 'error') {
              currentEvent = eventName as SSEEventType
            } else {
              currentEvent = ''
            }
          } else if (line.startsWith('data:') && currentEvent) {
            try {
              const jsonStr = line.slice(5).trim()
              if (!jsonStr) continue
              const data = JSON.parse(jsonStr)
              processSSEEvent(currentEvent, data)
            } catch (e) {
              // ignore
            }
          }
        }
      }

    } catch (e: any) {
      if (e.name === 'AbortError') {
        if (!assistantMsg.content) assistantMsg.content = '[已停止]'
      } else {
        error.value = '发送失败: ' + (e.message || '未知错误')
        messages.value.pop() // 移除助手占位
      }
    } finally {
      isLoading.value = false
      if (assistantMsg) assistantMsg.isStreaming = false
      abortController = null
      currentChatId.value = null
    }
  }

  // 保存 conversationId 到 localStorage
  const saveConversationId = (id: string) => {
    localStorage.setItem(getStorageKey(sessionName), id)
  }

  // 清除存储的 conversationId
  const clearStoredConversation = () => {
    localStorage.removeItem(getStorageKey(sessionName))
  }

  // 初始化：从 localStorage 恢复 conversationId
  const initConversation = async () => {
    const key = getStorageKey(sessionName)
    const storedConvId = localStorage.getItem(key)

    // 初始化 Session
    if (!chatToken.value) await initChatSession()

    if (storedConvId && storedConvId !== 'undefined') {
      conversationId.value = storedConvId
      await fetchMessageHistory(storedConvId)
    }
  }

  // 替换掉原来的 initConversation 调用，改为这里调用
  // 原代码 line 231 也会被覆盖，所以这里需要调用
  initConversation()

  // 复制文本到剪贴板
  const copyText = async (text: string): Promise<boolean> => {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      return false
    }
  }

  // TTS 朗读文本 - WebSocket 流式（经后端代理）
  const speakText = async (text: string, messageId: string) => {
    if (isSpeaking.value && speakingMessageId.value === messageId) {
      stopSpeaking()
      return
    }

    if (currentAudio) {
      stopSpeaking()
    }

    isSpeaking.value = true
    speakingMessageId.value = messageId

    const outputSampleRate = 24000
    const createAudioContext = () => {
      try {
        return new AudioContext({ sampleRate: outputSampleRate })
      } catch {
        return new AudioContext()
      }
    }

    try {
      const token = await getToken()
      const backendUrl = getBackendUrl().replace(/\/$/, '')
      const wsBaseUrl = backendUrl.replace(/^http/, 'ws')
      const wsUrl = `${wsBaseUrl}/api/chat/ws/tts?token=${encodeURIComponent(token)}`

      const ws = new WebSocket(wsUrl)
      let wsEventSeq = 0

      let audioContext: AudioContext | null = null
      let nextStartTime = 0
      const audioQueue: AudioBuffer[] = []
      let isPlaying = false
      let currentSource: AudioBufferSourceNode | null = null
      let stopped = false

      // 提前设置 currentAudio，避免消息被忽略
      currentAudio = {
        ws,
        getAudioContext: () => audioContext,
        getCurrentSource: () => currentSource,
        getAudioQueue: () => audioQueue,
        setStopped: (val: boolean) => { stopped = val }
      }

      const playNextBuffer = () => {
        if (stopped || !audioContext || audioQueue.length === 0) {
          isPlaying = false
          currentSource = null
          return
        }

        isPlaying = true
        const buffer = audioQueue.shift()!
        const source = audioContext.createBufferSource()
        currentSource = source
        source.buffer = buffer
        source.connect(audioContext.destination)

        const startTime = Math.max(audioContext.currentTime, nextStartTime)
        source.start(startTime)
        nextStartTime = startTime + buffer.duration

        source.onended = () => {
          if (!stopped && audioQueue.length > 0) {
            playNextBuffer()
          } else {
            isPlaying = false
            currentSource = null
          }
        }
      }

      ws.onopen = async () => {
        console.log('[TTS] connected')
      }

      ws.onmessage = async (event) => {
        if (!currentAudio || currentAudio.ws !== ws) return

        try {
          if (typeof event.data !== 'string') {
            return
          }
          const msg = JSON.parse(event.data)

          // 处理连接就绪事件
          if (msg.event_type === 'connection.ready') {
            audioContext = createAudioContext()
            nextStartTime = audioContext.currentTime

            try {
              if (audioContext.state === 'suspended') {
                await audioContext.resume()
              }
            } catch { }

            const voiceId = (config.public.cozeVoiceId as string) || ''
            const outputAudio: any = {
              codec: 'pcm',
              pcm_config: { sample_rate: outputSampleRate },
              speech_rate: 0
            }
            if (voiceId) outputAudio.voice_id = voiceId

            const configPayload = {
              id: `config-${Date.now()}-${wsEventSeq++}`,
              event_type: 'speech.update',
              data: { output_audio: outputAudio }
            }
            ws.send(JSON.stringify(configPayload))

            const chunks = text.match(/.{1,500}/g) || [text]
            chunks.forEach((chunk) => {
              ws.send(JSON.stringify({
                id: `text-${Date.now()}-${wsEventSeq++}`,
                event_type: 'input_text_buffer.append',
                data: { delta: chunk }
              }))
            })

            const completePayload = {
              id: `complete-${Date.now()}-${wsEventSeq++}`,
              event_type: 'input_text_buffer.complete'
            }
            ws.send(JSON.stringify(completePayload))
            return
          }

          if (msg.event_type === 'speech.audio.update' && msg.data?.delta) {
            const binaryStr = atob(msg.data.delta)
            const bytes = new Uint8Array(binaryStr.length)
            for (let i = 0; i < binaryStr.length; i++) {
              bytes[i] = binaryStr.charCodeAt(i)
            }

            const samples = new Float32Array(bytes.length / 2)
            const dataView = new DataView(bytes.buffer)
            for (let i = 0; i < samples.length; i++) {
              samples[i] = dataView.getInt16(i * 2, true) / 32768
            }

            if (audioContext) {
              const audioBuffer = audioContext.createBuffer(1, samples.length, outputSampleRate)
              audioBuffer.getChannelData(0).set(samples)
              audioQueue.push(audioBuffer)

              if (!isPlaying) playNextBuffer()
            }
          } else if (msg.event_type === 'speech.audio.completed') {
            ws.close()
          } else if (msg.event_type === 'error') {
            ws.close()
          }
        } catch (e) {
          console.error('[TTS] Failed to parse WS message', e)
        }
      }

      ws.onerror = (event) => {
        if (!currentAudio || currentAudio.ws !== ws) return
        console.error('[TTS] error', event)
        stopSpeaking()
      }

      ws.onclose = (event) => {
        if (!currentAudio || currentAudio.ws !== ws) return
        console.log('[TTS] closed', event.code, event.reason)

        const checkComplete = () => {
          if (audioQueue.length === 0 && !isPlaying) {
            try {
              if (audioContext) void audioContext.close()
            } catch { }

            if (speakingMessageId.value === messageId) {
              isSpeaking.value = false
              speakingMessageId.value = null
            }
            if (currentAudio?.ws === ws) currentAudio = null
          } else {
            setTimeout(checkComplete, 100)
          }
        }
        checkComplete()
      }

    } catch (e) {
      console.error('[TTS] Error:', e)
      stopSpeaking()
    }
  }

  // 停止朗读
  const stopSpeaking = () => {
    if (currentAudio) {
      try {
        currentAudio.setStopped(true)
      } catch { }

      try {
        const source = currentAudio.getCurrentSource()
        if (source) source.stop()
      } catch { }

      try {
        currentAudio.getAudioQueue().length = 0
      } catch { }

      try {
        currentAudio.ws.close()
      } catch { }

      try {
        const ctx = currentAudio.getAudioContext()
        if (ctx) void ctx.close()
      } catch { }

      currentAudio = null
    }

    isSpeaking.value = false
    speakingMessageId.value = null
  }

  return {
    messages,
    conversations,
    isLoading,
    isLoadingHistory,
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
  }
}
