<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chatWithAIStream } from '../api/ai'
import MarkdownBlock from './MarkdownBlock.vue'
import BrandMark from './BrandMark.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  /** 仓库列表页把当前页仓库传进来，供前方下拉选择 */
  repoOptions: { type: Array, default: () => [] },
  /** 登录用户名，用于顶部的流式问候 */
  username: { type: String, default: '' }
})

const repoChoices = computed(() =>
  (props.repoOptions || [])
    .filter((item) => item?.ownerName && item?.name)
    .map((item) => ({
      key: `${item.ownerName}/${item.name}`,
      owner: item.ownerName,
      repo: item.name
    }))
)

const selectedKey = ref('')
const selectedRepo = computed(
  () => repoChoices.value.find((item) => item.key === selectedKey.value) || null
)
const ready = computed(() => Boolean(selectedRepo.value))

/**
 * 提问模式：四种能力 + 自由。
 * 自由不改变 query；其余模式只在发送给后端时把「这是一个xxx」拼到前面，
 * 界面上通过消息上方的标签表达，用户输入本身保持原样。
 */
const MODES = [
  { value: 'free', label: '自由' },
  { value: 'review', label: '代码审查' },
  { value: 'search', label: '语义化搜索' },
  { value: 'archaeology', label: '代码考古' },
  { value: 'explain', label: '代码解释' }
]
const mode = ref('free')
const activeMode = computed(
  () => MODES.find((item) => item.value === mode.value) || MODES[0]
)

// 列表刷新后旧选择可能已不存在，落回第一项
watch(
  repoChoices,
  (choices) => {
    if (!choices.length) {
      selectedKey.value = ''
      return
    }
    if (choices.some((item) => item.key === selectedKey.value)) return
    selectedKey.value = choices[0].key
  },
  { immediate: true }
)

const input = ref('')
const loading = ref(false)
const messages = ref([])
const expanded = ref(false)
const listRef = ref(null)
const sessionId = ref(newSessionId())
// 中文输入法选词时按回车不能触发发送
const composing = ref(false)

// 每次发送自增，用来丢弃已中止流的迟到回调（新会话 / 切换仓库 / 组件卸载）
let streamSeq = 0
let activeController = null
let scrollQueued = false

const placeholder = computed(() => {
  if (!ready.value) return '请选择仓库'
  return expanded.value
    ? '继续追问，Enter 发送，Shift+Enter 换行'
    : '询问这个仓库的任何事，Enter 发送，Shift+Enter 换行'
})

// 顶部问候：挂载时按打字机节奏逐字输出，用户名变化后重播
const greeting = computed(() => `你好，${props.username || '开发者'}！`)
const typedGreeting = ref('')
const greetingTyping = ref(false)
let greetingTimer = null

function playGreeting() {
  if (greetingTimer !== null) {
    clearInterval(greetingTimer)
    greetingTimer = null
  }
  const full = greeting.value
  if (globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    typedGreeting.value = full
    greetingTyping.value = false
    return
  }
  typedGreeting.value = ''
  greetingTyping.value = true
  let index = 0
  greetingTimer = setInterval(() => {
    index += 1
    typedGreeting.value = full.slice(0, index)
    if (index >= full.length) {
      greetingTyping.value = false
      clearInterval(greetingTimer)
      greetingTimer = null
    }
  }, 80)
}

function newSessionId() {
  return (
    globalThis.crypto?.randomUUID?.() ||
    `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  )
}

function isNearBottom(el, threshold = 60) {
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < threshold
}

function scrollToBottom() {
  if (scrollQueued) return
  scrollQueued = true
  nextTick(() => {
    scrollQueued = false
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onEnter(event) {
  if (composing.value || event.isComposing) return
  event.preventDefault()
  send()
}

/** 丢弃当前流，不再改动消息内容 */
function cancelStream() {
  streamSeq += 1
  activeController?.abort()
  activeController = null
  loading.value = false
}

function reset() {
  cancelStream()
  sessionId.value = newSessionId()
  messages.value = []
  input.value = ''
  expanded.value = false
}

function stop() {
  activeController?.abort()
}

watch(selectedKey, () => {
  if (messages.value.length || loading.value) reset()
})

async function send() {
  const text = input.value.trim()
  if (!ready.value || !text || loading.value) return

  const modeLabel = activeMode.value.value === 'free' ? '' : activeMode.value.label
  const query = modeLabel ? `这是一个${modeLabel}\n\n${text}` : text

  // 首次发送时对话区自动展开，内容出现在输入框上方
  expanded.value = true
  messages.value.push({ id: Date.now(), role: 'user', content: text, modeLabel })
  messages.value.push({ id: Date.now() + 1, role: 'assistant', content: '', streaming: true })
  const index = messages.value.length - 1
  const { owner, repo } = selectedRepo.value
  input.value = ''
  loading.value = true
  scrollToBottom()

  const seq = ++streamSeq
  const controller = new AbortController()
  activeController = controller

  try {
    await chatWithAIStream(
      owner,
      repo,
      { session_id: sessionId.value, query },
      {
        signal: controller.signal,
        onToken: (token) => {
          if (seq !== streamSeq || !token) return
          const message = messages.value[index]
          if (!message) return
          const follow = isNearBottom(listRef.value)
          message.content += token
          if (follow) scrollToBottom()
        }
      }
    )
    if (seq !== streamSeq) return
    const message = messages.value[index]
    if (message) {
      message.streaming = false
      if (!message.content.trim()) message.content = '（AI 没有返回内容）'
    }
  } catch (error) {
    if (seq !== streamSeq) return
    const message = messages.value[index]
    if (message) {
      message.streaming = false
      if (error?.name === 'AbortError') {
        message.content = message.content
          ? `${message.content}\n\n（已停止生成）`
          : '（已停止生成）'
      } else {
        const detail = error?.message || '未知错误'
        message.content = message.content
          ? `${message.content}\n\n（生成中断：${detail}）`
          : `请求失败：${detail}`
      }
    }
  } finally {
    if (seq === streamSeq) {
      loading.value = false
      activeController = null
      scrollToBottom()
    }
  }
}

onMounted(playGreeting)
watch(() => props.username, playGreeting)

onBeforeUnmount(() => {
  if (greetingTimer !== null) clearInterval(greetingTimer)
  cancelStream()
})
</script>

<template>
  <div class="ai-shell">
    <div class="ask-greet">
      <h2>
        {{ typedGreeting }}<span v-if="greetingTyping" class="ask-caret" aria-hidden="true">▍</span>
      </h2>
    </div>

    <section class="ai-ask" :class="{ 'is-open': expanded }">
    <div class="ask-messages">
      <div class="ask-mask">
        <div class="ask-panel">
          <header class="ask-panel-head">
            <span class="ask-title">
              <el-icon><MagicStick /></el-icon>
              AI 助手
            </span>
            <span class="ask-scope mono">{{ selectedKey }}</span>
            <button type="button" class="ask-new" @click="reset">新会话</button>
          </header>

          <div ref="listRef" class="ask-list">
            <div
              v-for="message in messages"
              :key="message.id"
              class="ask-msg"
              :class="message.role"
            >
              <template v-if="message.role === 'user'">
                <div class="ask-msg-main">
                  <span v-if="message.modeLabel" class="ask-mode-tag">{{ message.modeLabel }}</span>
                  <span class="ask-bubble">{{ message.content }}</span>
                </div>
                <UserAvatar class="ask-user-avatar" :size="26" />
              </template>
              <template v-else>
                <BrandMark :size="26" class="ask-avatar" />
                <div class="ask-ai-main">
                  <div v-if="message.content" class="ask-ai-bubble">
                    <MarkdownBlock
                      :text="message.content"
                      :class="{ streaming: message.streaming }"
                    />
                  </div>
                  <div v-if="message.streaming" class="ask-thinking">
                    <span class="ask-spinner" aria-hidden="true"></span>
                    正在思考
                  </div>
                  <div v-else-if="!message.content" class="ask-ai-bubble pending">
                    （AI 没有返回内容）
                  </div>
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="ask-bar">
      <el-input
        v-model="input"
        class="ask-input"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 8 }"
        resize="none"
        :disabled="!ready"
        :placeholder="placeholder"
        @keydown.enter.exact="onEnter"
        @compositionstart="composing = true"
        @compositionend="composing = false"
      />

      <div class="ask-tools">
        <div class="ask-pill ask-repo">
          <el-icon class="ask-repo-icon"><Folder /></el-icon>
          <el-select
            v-model="selectedKey"
            class="ask-select"
            size="small"
            placeholder="选择仓库"
            filterable
            :disabled="!repoChoices.length"
          >
            <el-option
              v-for="item in repoChoices"
              :key="item.key"
              :label="item.key"
              :value="item.key"
            />
          </el-select>
        </div>

        <div class="ask-pill ask-mode-pill">
          <el-icon class="ask-mode-icon"><MagicStick /></el-icon>
          <el-select
            v-model="mode"
            class="ask-select ask-mode"
            size="small"
            title="提问模式"
          >
            <el-option
              v-for="item in MODES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </div>

        <el-button
          v-if="!loading"
          class="ask-send"
          type="primary"
          :disabled="!input.trim() || !ready"
          @click="send"
        >
          <el-icon><MagicStick /></el-icon>
          <span>发送</span>
        </el-button>
        <el-button
          v-else
          class="ask-send ask-stop"
          type="danger"
          plain
          title="停止生成"
          @click="stop"
        >
          <el-icon><VideoPause /></el-icon>
          <span>停止</span>
        </el-button>
      </div>
    </div>
    </section>
  </div>
</template>

<style scoped>
.ai-shell {
  width: 100%;
}

/* 顶部问候：挂载时逐字输出 */
.ask-greet {
  margin-bottom: 16px;
}

.ask-greet h2 {
  min-height: 38px;
  margin: 0;
  color: var(--text-0);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.35;
}

.ask-caret {
  color: var(--accent-2);
  animation: ask-blink 1s steps(1) infinite;
}

@keyframes ask-blink {
  50% {
    opacity: 0;
  }
}

.ai-ask {
  border: 1px solid var(--float-border);
  border-radius: 16px;
  background: var(--board-bg);
  box-shadow: var(--float-shadow);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

/* 聚焦只加深中性边框，不加蓝色外框 */
.ai-ask:focus-within {
  border-color: var(--line-strong);
  box-shadow: var(--float-shadow);
}

/* 0fr → 1fr 实现高度自适应展开，避免写死 max-height */
.ask-messages {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 280ms var(--ease);
}

.ai-ask.is-open .ask-messages {
  grid-template-rows: 1fr;
}

.ask-mask {
  min-height: 0;
  overflow: hidden;
}

.ask-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.ask-panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
  color: var(--text-3);
  font-size: 12px;
}

.ask-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-1);
  font-size: 12.5px;
  font-weight: 600;
}

.ask-title .el-icon {
  color: var(--accent-2);
  font-size: 14px;
}

.ask-scope {
  overflow: hidden;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ask-new {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.ask-new:hover {
  color: var(--link-hover);
}

.ask-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 340px;
  padding: 14px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.ask-msg {
  display: flex;
  flex-direction: column;
  min-width: 0;
  animation: ask-msg-in 0.24s var(--ease) both;
}

/* 头像在 DOM 里排在内容之后，普通 row 就能落在右侧 */
.ask-msg.user,
.ask-msg.assistant {
  flex-direction: row;
  align-items: flex-start;
  gap: 8px;
}

.ask-avatar,
.ask-user-avatar {
  margin-top: 2px;
}

/* 用户消息的标签和气泡靠右，头像在右侧 */
.ask-msg-main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: flex-end;
  max-width: calc(100% - 34px);
  min-width: 0;
}

.ask-ai-main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: flex-start;
  max-width: calc(100% - 34px);
  min-width: 0;
}

.ask-ai-bubble {
  max-width: 100%;
  padding: 10px 14px;
  border-radius: 4px 16px 16px 16px;
  background: var(--ai-bubble-bg);
  color: var(--text-1);
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
}

.ask-ai-bubble.pending {
  color: var(--text-3);
}

.ask-thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  color: var(--text-3);
  font-size: 12px;
}

.ask-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--line-strong);
  border-top-color: var(--accent-2);
  border-radius: 50%;
  animation: ask-spin 0.7s linear infinite;
}

@keyframes ask-spin {
  to {
    transform: rotate(360deg);
  }
}

.ask-mode-tag {
  margin-bottom: 4px;
  padding: 1px 8px;
  border: 1px solid oklch(0.54 0.19 274 / 0.24);
  border-radius: 999px;
  background: oklch(0.54 0.19 274 / 0.08);
  color: var(--accent-2);
  font-size: 11px;
  line-height: 17px;
}

.ask-bubble {
  max-width: 82%;
  padding: 9px 14px;
  border-radius: 16px 16px 4px 16px;
  background: var(--hover-2);
  color: var(--text-1);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.ask-msg.assistant :deep(.md-body.streaming)::after {
  content: '▍';
  margin-left: 1px;
  color: var(--accent-2);
  animation: ask-caret 1s steps(1) infinite;
}

@keyframes ask-msg-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes ask-caret {
  50% {
    opacity: 0;
  }
}

/* ---------- 输入区：多行输入 + 底部工具栏（仓库 / 模式 / 发送） ---------- */
.ask-bar {
  display: flex;
  flex-direction: column;
}

.ai-ask.is-open .ask-bar {
  border-top: 1px solid var(--line-soft);
}

.ask-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px 10px 14px;
}

/* 仓库 / 模式：带描边的浅灰胶囊，明确是可点击的选择器 */
.ask-pill {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  height: 30px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-1);
  transition: background-color 120ms var(--ease), border-color 120ms var(--ease);
}

.ask-pill:hover {
  border-color: var(--line-strong);
  background: var(--hover-2);
}

.ask-repo {
  gap: 5px;
  width: 178px;
  padding: 0 8px 0 10px;
}

.ask-mode-pill {
  gap: 4px;
  padding: 0 8px 0 10px;
}

.ask-repo-icon,
.ask-mode-icon {
  flex: 0 0 auto;
  color: var(--text-2);
  font-size: 14px;
}

.ask-select {
  flex: 1 1 auto;
  min-width: 0;
}

.ask-select :deep(.el-select__wrapper) {
  min-height: 24px;
  padding: 0;
  background: transparent;
  box-shadow: none;
}

/* 输入区内部一律不要蓝色的聚焦外框（浏览器 outline 和 Element 高亮都清掉） */
.ask-input :deep(.el-textarea__inner),
.ask-input :deep(.el-textarea__inner:hover),
.ask-input :deep(.el-textarea__inner:focus),
.ask-input :deep(.el-textarea__inner:focus-visible),
.ask-select :deep(.el-select__wrapper),
.ask-select :deep(.el-select__wrapper:hover),
.ask-select :deep(.el-select__wrapper.is-focused),
.ask-select :deep(.el-select__wrapper.is-hovering) {
  outline: none !important;
  box-shadow: none !important;
}

/* 单选的已选值也挂在 .el-select__placeholder 上，这里按状态区分颜色 */
.ask-select :deep(.el-select__placeholder) {
  color: var(--text-0);
  font-size: 12.5px;
  font-weight: 500;
}

.ask-select :deep(.el-select__placeholder.is-transparent) {
  color: var(--text-2);
  font-weight: 400;
}

.ask-select :deep(.el-select__caret) {
  color: var(--text-2);
  font-size: 13px;
}

.ask-mode {
  flex: 0 0 auto;
  width: 104px;
}

.ask-input {
  width: 100%;
}

.ask-input :deep(.el-textarea__inner) {
  padding: 14px 16px 6px;
  border-radius: 16px;
  background: transparent;
  box-shadow: none;
  color: var(--text-0);
  font-size: 14px;
  line-height: 1.6;
}

.ask-input :deep(.el-textarea__inner::placeholder) {
  color: var(--text-3);
  font-size: 13.5px;
}

.ask-send {
  flex: 0 0 auto;
  height: 34px;
  margin-left: auto;
  padding: 0 16px;
  border: 0;
  border-radius: 10px;
  background: var(--accent-1);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 2px 10px oklch(0.54 0.19 274 / 0.28);
}

.ask-send:hover,
.ask-send:focus {
  background: var(--accent-1);
  color: #fff;
  filter: brightness(0.93);
  box-shadow: 0 3px 12px oklch(0.54 0.19 274 / 0.32);
}

.ask-send .el-icon {
  font-size: 15px;
}

.ask-send.el-button--primary.is-disabled {
  background: var(--hover-1);
  color: var(--text-3);
  box-shadow: none;
  filter: none;
}

/* 深色下跟随全局：石板底 + 浅色字，不做发光蓝块 */
html.dark .ask-send.el-button {
  background: oklch(0.27 0.025 265);
  border: 1px solid oklch(0.64 0.17 274 / 0.34);
  color: var(--text-0);
  box-shadow: none;
}

html.dark .ask-send.el-button:hover,
html.dark .ask-send.el-button:focus {
  background: oklch(0.32 0.035 268);
  border-color: oklch(0.68 0.16 274 / 0.55);
  color: #fff;
  filter: none;
  box-shadow: none;
}

.ask-stop {
  background: rgba(248, 113, 113, 0.16);
  border: 1px solid rgba(248, 113, 113, 0.5);
  color: var(--accent-danger);
  box-shadow: none;
}

.ask-stop:hover,
.ask-stop:focus {
  background: rgba(248, 113, 113, 0.24);
  border-color: rgba(248, 113, 113, 0.7);
  color: var(--accent-danger);
  filter: none;
}

@media (max-width: 720px) {
  .ask-repo {
    width: 138px;
  }

  .ask-mode {
    width: 100px;
  }

  .ask-list {
    max-height: 280px;
  }
}

@media (max-width: 560px) {
  .ask-repo {
    width: 126px;
  }

  .ask-mode {
    width: 86px;
  }

  .ask-send {
    padding: 0 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ask-messages,
  .ask-msg {
    transition: none;
    animation: none;
  }

  .ask-spinner {
    animation-duration: 2s;
  }
}
</style>
