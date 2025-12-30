import { ref } from 'vue'

type TokenProvider = () => Promise<string> | string

type CozeWsEvent = {
  id: string
  event_type: string
  data?: unknown
}

/**
 * ASR 状态机
 * - idle: 空闲，未连接
 * - connecting: 正在建立连接，等待 Backend→Coze 就绪
 * - ready: 连接就绪，可以开始录音
 * - recording: 正在录音并发送音频
 * - completing: 已停止录音，等待最终结果
 * - closed: 连接已关闭
 */
type AsrState = 'idle' | 'connecting' | 'ready' | 'recording' | 'completing' | 'closed'

export function useVoiceInput(options: { getToken: TokenProvider }) {
  const config = useRuntimeConfig()

  // 对外暴露的状态
  const isRecording = ref(false)
  const isTranscribing = ref(false)
  const error = ref<string | null>(null)
  const recognizedText = ref('')

  // 内部状态机
  let state: AsrState = 'idle'

  // 音频相关
  let audioContext: AudioContext | null = null
  let mediaStream: MediaStream | null = null
  let scriptProcessor: ScriptProcessorNode | null = null
  let silentGain: GainNode | null = null

  // WebSocket 相关
  let ws: WebSocket | null = null
  const TARGET_SAMPLE_RATE = 24000 // Coze ASR 默认 24kHz
  let nativeSampleRate = 48000
  let wsEventSeq = 0

  // Promise 用于等待最终结果
  let finalPromise: Promise<string> | null = null
  let resolveFinal: ((text: string) => void) | null = null
  let rejectFinal: ((err: unknown) => void) | null = null

  const resolveToken = async (): Promise<string> => {
    const token = await options.getToken()
    if (!token) {
      throw new Error('缺少聊天 Token（X-Chat-Token）')
    }
    return token
  }

  const getBackendUrl = (): string => {
    return (config.public.chatServiceUrl as string) || 'http://localhost:8081'
  }

  const buildWsUrl = (token: string): string => {
    const backendUrl = getBackendUrl().replace(/\/$/, '')
    const wsBaseUrl = backendUrl.replace(/^http/, 'ws')
    return `${wsBaseUrl}/api/chat/ws/asr?token=${encodeURIComponent(token)}`
  }

  const cleanupAudio = () => {
    if (scriptProcessor) {
      try {
        scriptProcessor.disconnect()
      } catch { }
      scriptProcessor.onaudioprocess = null
      scriptProcessor = null
    }

    if (silentGain) {
      try {
        silentGain.disconnect()
      } catch { }
      silentGain = null
    }

    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop())
      mediaStream = null
    }

    if (audioContext) {
      try {
        void audioContext.close()
      } catch { }
      audioContext = null
    }
  }

  const cleanupWs = () => {
    if (ws) {
      try {
        ws.close()
      } catch { }
      ws.onopen = null
      ws.onmessage = null
      ws.onerror = null
      ws.onclose = null
      ws = null
    }
    wsEventSeq = 0
  }

  const cleanup = () => {
    cleanupAudio()
    cleanupWs()
    state = 'idle'
    finalPromise = null
    resolveFinal = null
    rejectFinal = null
  }

  const bytesToBase64 = (bytes: Uint8Array): string => {
    let binary = ''
    const chunkSize = 0x8000
    for (let i = 0; i < bytes.length; i += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize))
    }
    return btoa(binary)
  }

  const float32ToPcm16Bytes = (float32: Float32Array): Uint8Array => {
    const buffer = new ArrayBuffer(float32.length * 2)
    const view = new DataView(buffer)
    for (let i = 0; i < float32.length; i++) {
      const s = Math.max(-1, Math.min(1, float32[i]))
      const val = s < 0 ? s * 0x8000 : s * 0x7fff
      view.setInt16(i * 2, val, true)
    }
    return new Uint8Array(buffer)
  }

  // 降采样：从 nativeSampleRate 降到 TARGET_SAMPLE_RATE
  // 使用带简单低通滤波的平均法，避免混叠失真
  const downsample = (input: Float32Array, fromRate: number, toRate: number): Float32Array => {
    if (fromRate === toRate) return input
    const ratio = fromRate / toRate
    const outputLength = Math.floor(input.length / ratio)
    const output = new Float32Array(outputLength)

    // 整数比例时用均值滤波（更高质量）
    const intRatio = Math.round(ratio)
    if (Math.abs(ratio - intRatio) < 0.01 && intRatio >= 2) {
      for (let i = 0; i < outputLength; i++) {
        let sum = 0
        const start = i * intRatio
        for (let j = 0; j < intRatio; j++) {
          sum += input[start + j] || 0
        }
        output[i] = sum / intRatio
      }
    } else {
      // 非整数比例用线性插值
      for (let i = 0; i < outputLength; i++) {
        const srcIdx = i * ratio
        const floor = Math.floor(srcIdx)
        const ceil = Math.min(floor + 1, input.length - 1)
        const t = srcIdx - floor
        output[i] = input[floor] * (1 - t) + input[ceil] * t
      }
    }
    return output
  }

  const sendWsEvent = (event_type: string, data?: unknown): boolean => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false

    const payload: CozeWsEvent = {
      id: `${event_type}-${Date.now()}-${wsEventSeq++}`,
      event_type
    }
    if (data !== undefined) payload.data = data

    ws.send(JSON.stringify(payload))
    return true
  }

  const sendConfig = () => {
    sendWsEvent('transcriptions.update', {
      input_audio: {
        format: 'pcm',
        codec: 'pcm',
        sample_rate: TARGET_SAMPLE_RATE,
        channel: 1,
        bit_depth: 16
      },
      asr_config: {
        // 关闭语义顺滑，保留语气词（"啊"、"嗯"、"吗"等）
        enable_ddc: false,
        // 保持文本规范化（数字格式转换）
        enable_itn: true,
        // 保持标点符号
        enable_punc: true
      }
    })
  }

  const startRecording = async (): Promise<void> => {
    if (state !== 'idle') return

    cleanup()
    error.value = null
    recognizedText.value = ''
    isRecording.value = true
    state = 'connecting'

    // 初始化 Promise 用于等待最终结果
    finalPromise = new Promise<string>((resolve, reject) => {
      resolveFinal = resolve
      rejectFinal = reject
    })

    let token: string
    try {
      token = await resolveToken()
    } catch (e) {
      error.value = e instanceof Error ? e.message : '获取 Token 失败'
      cleanup()
      isRecording.value = false
      throw e
    }

    try {
      ws = new WebSocket(buildWsUrl(token))

      ws.onopen = () => {
        console.log('[ASR] WebSocket connected to backend, waiting for ready...')
      }

      ws.onmessage = (event) => {
        try {
          console.log('[ASR] Received:', event.data)
          const msg = JSON.parse(event.data)
          const eventType = msg?.event_type

          if (eventType === 'connection.ready' || eventType === 'transcriptions.created') {
            // 后端已连上 Coze，可以开始发送数据
            if (state === 'connecting') {
              console.log('[ASR] Connection ready, sending config...')
              state = 'ready'
              sendConfig()
              // 震动反馈
              if (window.navigator && window.navigator.vibrate) {
                window.navigator.vibrate(50)
              }
            }
          } else if (eventType === 'transcriptions.updated') {
            // Config 已确认，开始录音
            if (state === 'ready') {
              console.log('[ASR] Config confirmed, starting audio capture...')
              state = 'recording'
            }
          } else if (eventType === 'transcriptions.message.update' && typeof msg?.data?.content === 'string') {
            console.log('[ASR] Partial result:', msg.data.content)
            recognizedText.value = msg.data.content
          } else if (eventType === 'transcriptions.message.completed') {
            // 尝试从 completed 事件提取最终文本
            const finalContent = msg?.data?.content ?? msg?.data?.text ?? msg?.content ?? ''
            if (finalContent && typeof finalContent === 'string') {
              recognizedText.value = finalContent
            }
            console.log('[ASR] Recognition completed:', recognizedText.value, 'Full msg:', JSON.stringify(msg))
            resolveFinal?.(recognizedText.value)
            state = 'closed'
            cleanupWs()
          } else if (eventType === 'error') {
            const errMsg = msg?.data?.msg || 'ASR 发生错误'
            console.error('[ASR] Error event:', errMsg)
            rejectFinal?.(new Error(errMsg))
            state = 'closed'
            cleanupWs()
          }
        } catch (e) {
          console.error('[ASR] Error parsing message:', e)
        }
      }

      ws.onerror = (e) => {
        console.error('[ASR] WebSocket error:', e)
        rejectFinal?.(new Error('ASR WebSocket 连接失败'))
        state = 'closed'
        cleanup()
      }

      ws.onclose = (e) => {
        console.log('[ASR] WebSocket closed:', e.code, e.reason)
        if (state !== 'closed') {
          const text = recognizedText.value
          if (text) {
            resolveFinal?.(text)
          } else if (resolveFinal || rejectFinal) {
            rejectFinal?.(new Error(`ASR WebSocket 已断开 (Code: ${e.code})`))
          }
        }
        state = 'closed'
        cleanupWs()
      }

      // 初始化音频
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true
        }
      })

      try {
        audioContext = new AudioContext()
      } catch {
        audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      }

      if (audioContext.state === 'suspended') {
        await audioContext.resume()
      }

      nativeSampleRate = audioContext.sampleRate
      console.log('[ASR] AudioContext ready, nativeSampleRate:', nativeSampleRate, 'target:', TARGET_SAMPLE_RATE)

      const source = audioContext.createMediaStreamSource(mediaStream)
      scriptProcessor = audioContext.createScriptProcessor(4096, 1, 1)

      silentGain = audioContext.createGain()
      silentGain.gain.value = 0

      scriptProcessor.onaudioprocess = (e) => {
        // 只有在 recording 状态才发送音频
        if (state !== 'recording') {
          console.log('[ASR] onaudioprocess skipped, state:', state)
          return
        }
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          console.log('[ASR] onaudioprocess skipped, ws not ready')
          return
        }

        const input = e.inputBuffer.getChannelData(0)
        // 降采样到 16kHz
        const resampled = downsample(input, nativeSampleRate, TARGET_SAMPLE_RATE)
        const pcmBytes = float32ToPcm16Bytes(resampled)
        const delta = bytesToBase64(pcmBytes)
        const sent = sendWsEvent('input_audio_buffer.append', { delta })
        console.log('[ASR] Audio chunk sent:', sent, 'bytes:', pcmBytes.length)
      }

      source.connect(scriptProcessor)
      scriptProcessor.connect(silentGain)
      silentGain.connect(audioContext.destination)

      // 设置连接超时
      setTimeout(() => {
        if (state === 'connecting') {
          error.value = 'ASR 连接超时'
          rejectFinal?.(new Error('ASR 连接超时'))
          cleanup()
          isRecording.value = false
        }
      }, 10000)

    } catch (e) {
      cleanup()
      isRecording.value = false
      error.value = e instanceof Error ? e.message : '无法启动录音/ASR'
      throw e
    }
  }

  const stopRecording = async (): Promise<string> => {
    console.log('[ASR] stopRecording called, state:', state)
    isRecording.value = false
    isTranscribing.value = true

    // 发送完成信号
    if (state === 'recording' && ws && ws.readyState === WebSocket.OPEN) {
      console.log('[ASR] Sending input_audio_buffer.complete...')
      sendWsEvent('input_audio_buffer.complete')
      state = 'completing'
    } else {
      console.log('[ASR] Cannot send complete signal, state:', state, 'ws:', ws?.readyState)
    }

    // 等待最终结果
    if (finalPromise) {
      const timeoutPromise = new Promise<string>((resolve) => {
        setTimeout(() => resolve(recognizedText.value), 5000)
      })
      const result = await Promise.race([finalPromise, timeoutPromise])
      isTranscribing.value = false
      cleanup()
      return result || recognizedText.value
    }

    isTranscribing.value = false
    const result = recognizedText.value
    cleanup()
    return result
  }

  const cancelRecording = () => {
    isRecording.value = false
    isTranscribing.value = false
    recognizedText.value = ''
    rejectFinal?.(new Error('用户取消'))
    cleanup()
  }

  return {
    isRecording,
    isTranscribing,
    error,
    startRecording,
    stopRecording,
    cancelRecording,
    recognizedText
  }
}
