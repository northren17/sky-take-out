<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChatDotRound, Close, Microphone, Phone, VideoPause } from '@element-plus/icons-vue'
import { issueVoiceTicket } from '@/api/modules/user'
import { SUCCESS_CODE } from '@/constants/business'
import { useUserStore } from '@/stores/user'

type ConnectionState = 'closed' | 'connecting' | 'starting' | 'connected' | 'error'
type MessageRole = 'user' | 'assistant'

interface ChatMessage {
  id: number
  role: MessageRole
  text: string
}

interface VoiceEvent {
  type?: string
  session_id?: string
  turn_id?: number | null
  seq?: number
  text?: string
  state?: string
  message?: string
  input_audio?: { encoding?: string; sample_rate?: number }
}

const INPUT_SAMPLE_RATE = 16_000
const OUTPUT_SAMPLE_RATE = 24_000
const AUDIO_HEADER_SIZE = 8
const AUDIO_MAGIC = [0x56, 0x41, 0x31, 0x00]

const user = useUserStore()
const isOpen = ref(false)
const connectionState = ref<ConnectionState>('closed')
const serverState = ref('')
const interimTranscript = ref('')
const messages = ref<ChatMessage[]>([])
const messageList = ref<HTMLElement>()
const microphoneError = ref('')

const isTalking = ref(false)
const isFinishingInput = ref(false)
const canTalk = computed(
  () =>
    connectionState.value === 'connected' &&
    serverState.value === 'listening' &&
    !isFinishingInput.value,
)
const statusText = computed(() => {
  if (connectionState.value === 'connecting') return '正在验证语音会话…'
  if (connectionState.value === 'starting') return '会话已验证，正在连接语音服务…'
  if (connectionState.value === 'error') return '语音服务暂不可用'
  if (connectionState.value === 'closed') return '语音助手待连接'
  if (isTalking.value) return '正在聆听，请松开发送'
  if (isFinishingInput.value) return '正在识别…'
  if (serverState.value === 'thinking') return '正在识别并查询…'
  if (serverState.value === 'speaking') return '正在语音回复'
  if (serverState.value === 'error') return '本轮处理失败'
  return '已连接，按住说话'
})

let socket: WebSocket | null = null
let heartbeatTimer: number | undefined
let generation = 0
let lastEventSequence = 0
let nextMessageId = 0
let activeTurnId = 0
let assistantMessageId: number | undefined
let audioContext: AudioContext | undefined
let microphoneStream: MediaStream | undefined
let microphoneSource: MediaStreamAudioSourceNode | undefined
let microphoneProcessor: ScriptProcessorNode | undefined
let silentGain: GainNode | undefined
let playbackCursor = 0
let pressRequested = false
const playbackSources = new Set<AudioBufferSourceNode>()

function pushMessage(role: MessageRole, text: string): number {
  const id = ++nextMessageId
  messages.value.push({ id, role, text })
  if (messages.value.length > 60) messages.value.shift()
  void scrollMessagesToEnd()
  return id
}

async function scrollMessagesToEnd() {
  await Promise.resolve()
  if (messageList.value) {
    messageList.value.scrollTop = messageList.value.scrollHeight
  }
}

function websocketUrl(): string {
  const configured = import.meta.env.VITE_AGENT_WS_URL
  if (configured) return configured
  const url = new URL('/ws/voice', window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

function isCurrentConnection(currentGeneration: number, currentSocket: WebSocket): boolean {
  return generation === currentGeneration && socket === currentSocket
}

function clearHeartbeat() {
  if (heartbeatTimer !== undefined) {
    window.clearInterval(heartbeatTimer)
    heartbeatTimer = undefined
  }
}

function clearPlayback() {
  for (const source of playbackSources) {
    try {
      source.stop()
    } catch {
      // A source may already have ended before a playback-clear event arrives.
    }
  }
  playbackSources.clear()
  playbackCursor = 0
}

function disposeMicrophone() {
  pressRequested = false
  isTalking.value = false
  isFinishingInput.value = false
  if (microphoneProcessor) {
    microphoneProcessor.onaudioprocess = null
    microphoneProcessor.disconnect()
    microphoneProcessor = undefined
  }
  microphoneSource?.disconnect()
  microphoneSource = undefined
  silentGain?.disconnect()
  silentGain = undefined
  microphoneStream?.getTracks().forEach((track) => track.stop())
  microphoneStream = undefined
  if (audioContext && audioContext.state !== 'closed') {
    void audioContext.close()
  }
  audioContext = undefined
  clearPlayback()
}

function disconnect(clearMessages = false) {
  generation += 1
  clearHeartbeat()
  disposeMicrophone()
  const currentSocket = socket
  socket = null
  if (currentSocket && currentSocket.readyState === WebSocket.OPEN) {
    currentSocket.send(JSON.stringify({ type: 'session.stop' }))
  }
  if (
    currentSocket &&
    currentSocket.readyState !== WebSocket.CLOSED &&
    currentSocket.readyState !== WebSocket.CLOSING
  ) {
    currentSocket.close(1000, 'Client closed voice session')
  }
  connectionState.value = 'closed'
  serverState.value = ''
  interimTranscript.value = ''
  microphoneError.value = ''
  activeTurnId = 0
  assistantMessageId = undefined
  lastEventSequence = 0
  if (clearMessages) messages.value = []
}

function appendAssistantDelta(turnId: number | null | undefined, text: string) {
  if (!text || !turnId || turnId < activeTurnId) return
  if (turnId > activeTurnId) {
    activeTurnId = turnId
    assistantMessageId = undefined
  }
  const message = messages.value.find((item) => item.id === assistantMessageId)
  if (message) message.text += text
  else assistantMessageId = pushMessage('assistant', text)
  void scrollMessagesToEnd()
}

function handleServerEvent(event: VoiceEvent) {
  if (typeof event.seq === 'number') {
    if (event.seq <= lastEventSequence) return
    lastEventSequence = event.seq
  }
  if (typeof event.turn_id === 'number' && event.turn_id < activeTurnId) return

  switch (event.type) {
    case 'session.ready':
      serverState.value = 'connecting'
      connectionState.value = 'starting'
      break
    case 'state.changed':
      if (typeof event.turn_id === 'number' && event.turn_id > activeTurnId) {
        activeTurnId = event.turn_id
        assistantMessageId = undefined
      }
      serverState.value = event.state ?? ''
      if (event.state === 'listening') {
        connectionState.value = 'connected'
        interimTranscript.value = ''
      } else if (event.state === 'error') {
        connectionState.value = 'error'
      }
      break
    case 'stt.partial':
      if (typeof event.turn_id === 'number') activeTurnId = event.turn_id
      interimTranscript.value = event.text ?? ''
      break
    case 'stt.final':
      if (typeof event.turn_id === 'number') activeTurnId = event.turn_id
      interimTranscript.value = ''
      if (event.text) pushMessage('user', event.text)
      assistantMessageId = undefined
      break
    case 'agent.delta':
      appendAssistantDelta(event.turn_id, event.text ?? '')
      break
    case 'agent.finished':
      if (typeof event.turn_id === 'number') activeTurnId = event.turn_id
      break
    case 'playback.clear':
      clearPlayback()
      break
    case 'error':
      if (event.message) pushMessage('assistant', event.message)
      if (event.state === 'error' || event.type === 'error') {
        serverState.value = 'error'
        connectionState.value = 'error'
      }
      break
    case 'heartbeat.ack':
      break
  }
}

function playAudioFrame(frame: ArrayBuffer) {
  if (frame.byteLength <= AUDIO_HEADER_SIZE) return
  const bytes = new Uint8Array(frame)
  if (!AUDIO_MAGIC.every((value, index) => bytes[index] === value)) return
  const turnId = new DataView(frame).getUint32(4, false)
  if (turnId < activeTurnId || !audioContext) return
  activeTurnId = Math.max(activeTurnId, turnId)
  const sampleCount = Math.floor((frame.byteLength - AUDIO_HEADER_SIZE) / 2)
  if (!sampleCount) return

  const samples = new Int16Array(frame, AUDIO_HEADER_SIZE, sampleCount)
  const buffer = audioContext.createBuffer(1, sampleCount, OUTPUT_SAMPLE_RATE)
  const channel = buffer.getChannelData(0)
  for (let index = 0; index < sampleCount; index += 1) {
    channel[index] = samples[index] / 32768
  }

  const source = audioContext.createBufferSource()
  source.buffer = buffer
  source.connect(audioContext.destination)
  source.onended = () => playbackSources.delete(source)
  playbackSources.add(source)
  const startAt = Math.max(playbackCursor, audioContext.currentTime)
  source.start(startAt)
  playbackCursor = startAt + buffer.duration
}

async function connect() {
  if (!user.token || connectionState.value === 'connecting') return
  disconnect(true)
  const currentGeneration = generation
  connectionState.value = 'connecting'
  microphoneError.value = ''

  let currentSocket: WebSocket | undefined
  try {
    const result = await issueVoiceTicket()
    if (generation !== currentGeneration) return
    if (![SUCCESS_CODE, 200].includes(result.code) || !result.data?.ticket) {
      throw new Error(result.msg || '无法创建语音会话')
    }

    currentSocket = new WebSocket(websocketUrl())
    currentSocket.binaryType = 'arraybuffer'
    socket = currentSocket

    await new Promise<void>((resolve, reject) => {
      const authTimeout = window.setTimeout(
        () => reject(new Error('语音认证超时，请重试')),
        12_000,
      )
      const finish = (error?: Error) => {
        window.clearTimeout(authTimeout)
        if (error) reject(error)
        else resolve()
      }

      currentSocket!.onopen = () => {
        if (!isCurrentConnection(currentGeneration, currentSocket!)) return
        currentSocket!.send(JSON.stringify({ type: 'auth', ticket: result.data!.ticket }))
      }
      currentSocket!.onmessage = (message) => {
        if (!isCurrentConnection(currentGeneration, currentSocket!)) return
        if (typeof message.data !== 'string') {
          if (message.data instanceof ArrayBuffer) playAudioFrame(message.data)
          return
        }
        let event: VoiceEvent
        try {
          event = JSON.parse(message.data) as VoiceEvent
        } catch {
          return
        }
        handleServerEvent(event)
        if (event.type === 'session.ready') finish()
      }
      currentSocket!.onerror = () => finish(new Error('无法连接语音服务'))
      currentSocket!.onclose = (event) => {
        if (!isCurrentConnection(currentGeneration, currentSocket!)) return
        if (connectionState.value !== 'closed') {
          connectionState.value = 'error'
          serverState.value = ''
          microphoneError.value =
            event.code === 4401
              ? '会话无效或已过期，请重新连接'
              : event.code === 4403
                ? '当前页面来源未获语音服务允许'
                : '语音连接已断开，请重新连接'
        }
        clearHeartbeat()
        disposeMicrophone()
        socket = null
        finish(new Error(microphoneError.value || '语音连接已关闭'))
      }
    })

    if (!isCurrentConnection(currentGeneration, currentSocket)) return
    heartbeatTimer = window.setInterval(() => {
      if (currentSocket?.readyState === WebSocket.OPEN) {
        currentSocket.send(JSON.stringify({ type: 'heartbeat' }))
      }
    }, 20_000)
  } catch (error) {
    if (generation !== currentGeneration) return
    connectionState.value = 'error'
    microphoneError.value =
      error instanceof Error ? error.message : '无法连接语音服务，请稍后重试'
    if (currentSocket && currentSocket.readyState < WebSocket.CLOSING) {
      currentSocket.close()
    }
  }
}

function resampleToPcm(input: Float32Array, inputSampleRate: number): Uint8Array {
  const outputLength = Math.floor((input.length * INPUT_SAMPLE_RATE) / inputSampleRate)
  const output = new Uint8Array(outputLength * 2)
  const view = new DataView(output.buffer)
  const ratio = inputSampleRate / INPUT_SAMPLE_RATE
  for (let index = 0; index < outputLength; index += 1) {
    const position = index * ratio
    const left = Math.floor(position)
    const right = Math.min(left + 1, input.length - 1)
    const fraction = position - left
    const sample = Math.max(-1, Math.min(1, input[left] * (1 - fraction) + input[right] * fraction))
    view.setInt16(index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
  }
  return output
}

async function prepareMicrophone() {
  if (audioContext && microphoneProcessor && microphoneStream?.active) return
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('当前浏览器不支持麦克风，或页面不是安全的 HTTPS 来源')
  }
  microphoneStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  })
  audioContext = new AudioContext({ sampleRate: INPUT_SAMPLE_RATE })
  await audioContext.resume()
  microphoneSource = audioContext.createMediaStreamSource(microphoneStream)
  microphoneProcessor = audioContext.createScriptProcessor(4096, 1, 1)
  silentGain = audioContext.createGain()
  silentGain.gain.value = 0
  microphoneProcessor.onaudioprocess = (event) => {
    const currentSocket = socket
    if (!isTalking.value || currentSocket?.readyState !== WebSocket.OPEN || !audioContext) {
      return
    }
    const pcm = resampleToPcm(event.inputBuffer.getChannelData(0), audioContext.sampleRate)
    if (pcm.byteLength) currentSocket.send(pcm)
  }
  microphoneSource.connect(microphoneProcessor)
  microphoneProcessor.connect(silentGain)
  silentGain.connect(audioContext.destination)
}

async function beginSpeech(event?: PointerEvent) {
  if (!canTalk.value || isTalking.value || pressRequested) return
  pressRequested = true
  if (event?.currentTarget instanceof HTMLButtonElement) {
    event.currentTarget.setPointerCapture(event.pointerId)
  }
  microphoneError.value = ''
  try {
    await prepareMicrophone()
    if (!pressRequested || !canTalk.value) {
      disposeMicrophone()
      return
    }
    isTalking.value = true
  } catch (error) {
    disposeMicrophone()
    microphoneError.value =
      error instanceof Error ? error.message : '无法访问麦克风，请检查浏览器权限'
  }
}

function beginKeyboardSpeech() {
  void beginSpeech()
}

function toggleAssistant() {
  isOpen.value = !isOpen.value
  if (!isOpen.value) disconnect(true)
}

function closeAssistant() {
  disconnect(true)
  isOpen.value = false
}

async function endSpeech() {
  pressRequested = false
  if (!isTalking.value) return
  isTalking.value = false
  const currentSocket = socket
  if (!currentSocket || currentSocket.readyState !== WebSocket.OPEN) return
  const silence = new Uint8Array(INPUT_SAMPLE_RATE / 10 * 2)
  isFinishingInput.value = true
  try {
    for (let index = 0; index < 10; index += 1) {
      if (currentSocket.readyState !== WebSocket.OPEN || socket !== currentSocket) return
      currentSocket.send(silence)
      await new Promise((resolve) => window.setTimeout(resolve, 100))
    }
  } finally {
    isFinishingInput.value = false
  }
}

function interrupt() {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type: 'interrupt' }))
  }
}

watch(
  () => user.token,
  (token, previousToken) => {
    if (previousToken && token !== previousToken) disconnect(true)
  },
)

onBeforeUnmount(() => disconnect(true))
</script>

<template>
  <div class="voice-assistant">
    <section v-if="isOpen" class="voice-panel" aria-label="语音助手">
      <header class="panel-header">
        <div>
          <strong>苍穹语音助手</strong>
          <p :class="{ 'status-error': connectionState === 'error' }" aria-live="polite">
            {{ statusText }}
          </p>
        </div>
        <button class="icon-button" type="button" aria-label="关闭语音助手" @click="closeAssistant">
          <el-icon><Close /></el-icon>
        </button>
      </header>

      <div ref="messageList" class="message-list" aria-live="polite" aria-relevant="additions text">
        <p v-if="!messages.length" class="welcome-message">
          可查询本人近期订单和优惠券。语音仅用于本次会话，不保存原始音频。
        </p>
        <article
          v-for="message in messages"
          :key="message.id"
          class="message"
          :class="`message-${message.role}`"
        >
          <span>{{ message.role === 'user' ? '我' : '助手' }}</span>
          <p>{{ message.text }}</p>
        </article>
        <article v-if="interimTranscript" class="message message-user message-interim">
          <span>识别中</span>
          <p>{{ interimTranscript }}</p>
        </article>
      </div>

      <p v-if="microphoneError" class="inline-error" role="alert">{{ microphoneError }}</p>

      <div class="panel-controls">
        <button
          v-if="connectionState === 'closed' || connectionState === 'error' || connectionState === 'connecting'"
          class="connect-button"
          type="button"
          :disabled="connectionState === 'connecting'"
          @click="connect"
        >
          <el-icon><Phone /></el-icon>
          {{
            connectionState === 'connecting'
              ? '正在验证…'
              : connectionState === 'error'
                ? '重新连接'
                : '连接语音助手'
          }}
        </button>
        <template v-else>
          <button
            v-if="canTalk || isTalking"
            class="talk-button"
            :class="{ 'is-talking': isTalking }"
            type="button"
            :aria-label="isTalking ? '松开结束说话' : '按住说话'"
            @pointerdown.prevent="beginSpeech"
            @pointerup.prevent="endSpeech"
            @pointercancel.prevent="endSpeech"
            @lostpointercapture="endSpeech"
            @keydown.space.prevent="beginKeyboardSpeech"
            @keyup.space.prevent="endSpeech"
          >
            <el-icon><Microphone /></el-icon>
            {{ isTalking ? '松开发送' : '按住说话' }}
          </button>
          <button
            v-else-if="serverState === 'thinking' || serverState === 'speaking'"
            class="interrupt-button"
            type="button"
            @click="interrupt"
          >
            <el-icon><VideoPause /></el-icon>
            打断回复
          </button>
          <button v-else class="connect-button" type="button" disabled>
            {{ connectionState === 'starting' ? '正在准备…' : '等待语音服务…' }}
          </button>
          <button class="disconnect-button" type="button" @click="disconnect(true)">
            结束会话
          </button>
        </template>
      </div>
      <p class="privacy-note">仅查询本人订单与优惠券，不支持取消或退款。</p>
    </section>

    <button
      class="assistant-launcher"
      type="button"
      :aria-expanded="isOpen"
      aria-label="打开苍穹语音助手"
      @click="toggleAssistant"
    >
      <el-icon><Close v-if="isOpen" /><ChatDotRound v-else /></el-icon>
      <span>{{ isOpen ? '收起' : '语音助手' }}</span>
    </button>
  </div>
</template>

<style scoped>
.voice-assistant {
  position: fixed;
  right: max(24px, env(safe-area-inset-right));
  bottom: max(24px, env(safe-area-inset-bottom));
  z-index: 80;
  font-family: inherit;
}

.assistant-launcher,
.connect-button,
.talk-button,
.interrupt-button,
.disconnect-button,
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.assistant-launcher {
  min-height: 48px;
  padding: 0 18px;
  border-radius: 999px;
  background: #bd6b27;
  box-shadow: 0 8px 24px rgb(90 53 24 / 24%);
  color: #fff;
  font-size: 14px;
  font-weight: 650;
}

.assistant-launcher:hover,
.connect-button:hover:not(:disabled),
.talk-button:hover:not(:disabled) {
  background: #a9561d;
}

.voice-panel {
  position: absolute;
  right: 0;
  bottom: 62px;
  display: flex;
  flex-direction: column;
  width: min(380px, calc(100vw - 28px));
  max-height: min(580px, calc(100dvh - 120px));
  overflow: hidden;
  border: 1px solid #e6e9ef;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 18px 55px rgb(24 40 62 / 20%);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 66px;
  padding: 12px 16px;
  border-bottom: 1px solid #edf0f4;
}

.panel-header strong {
  color: #252a33;
  font-size: 15px;
}

.panel-header p {
  margin: 4px 0 0;
  color: #76808d;
  font-size: 12px;
}

.panel-header .status-error,
.inline-error {
  color: #b42318;
}

.icon-button {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: transparent;
  color: #647080;
}

.icon-button:hover {
  background: #f2f4f7;
}

.message-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-height: 150px;
  max-height: 330px;
  overflow-y: auto;
  padding: 16px;
  overscroll-behavior: contain;
}

.welcome-message {
  margin: auto 8px;
  color: #76808d;
  font-size: 13px;
  line-height: 1.7;
  text-align: center;
}

.message {
  max-width: 88%;
  padding: 9px 12px;
  border-radius: 12px;
  background: #f3f5f8;
  color: #303743;
  overflow-wrap: anywhere;
}

.message-user {
  align-self: flex-end;
  background: #fff3e5;
}

.message span {
  color: #8a929e;
  font-size: 10px;
}

.message p {
  margin: 3px 0 0;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.message-interim {
  opacity: 0.72;
}

.inline-error {
  margin: 0;
  padding: 0 16px 10px;
  font-size: 12px;
}

.panel-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 16px 8px;
}

.connect-button,
.talk-button,
.interrupt-button {
  min-height: 42px;
  padding: 0 17px;
  border-radius: 999px;
  background: #bd6b27;
  color: #fff;
  font-size: 13px;
  font-weight: 650;
}

.connect-button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.talk-button {
  min-width: 150px;
  touch-action: none;
  user-select: none;
}

.talk-button.is-talking {
  background: #9e391e;
  box-shadow: 0 0 0 5px rgb(189 107 39 / 15%);
}

.interrupt-button {
  background: #424b59;
}

.disconnect-button {
  min-height: 38px;
  padding: 0 10px;
  border-radius: 999px;
  background: transparent;
  color: #6c7480;
  font-size: 12px;
}

.disconnect-button:hover {
  background: #f3f5f8;
}

.privacy-note {
  margin: 0;
  padding: 0 12px 12px;
  color: #89919c;
  font-size: 11px;
  text-align: center;
}

@media (max-width: 600px) {
  .voice-assistant {
    right: 14px;
    bottom: calc(78px + env(safe-area-inset-bottom));
  }

  .voice-panel {
    bottom: 58px;
    max-height: calc(100dvh - 150px - env(safe-area-inset-bottom));
  }

  .message-list {
    max-height: 38dvh;
  }
}
</style>
