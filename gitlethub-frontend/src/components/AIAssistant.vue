<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { chatWithAIStream } from '../api/ai'
import { renderMarkdown } from '../utils/markdown'
import BrandMark from './BrandMark.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  owner: { type: String, default: '' },
  repo: { type: String, default: '' },
  /** 传入仓库列表时，抽屉内显示仓库下拉（仓库列表页用） */
  repoOptions: { type: Array, default: () => [] },
  /** PR 主键；传了则服务端自动组装 diff，走代码审查场景 */
  prId: { type: [Number, String], default: null },
  /** 普通问答时可附加的上下文（例如提交信息 + 变更代码），只在首轮发送 */
  contextText: { type: String, default: '' },
  label: { type: String, default: 'AI 助手' },
  /** 触发按钮占满容器宽度 */
  block: { type: Boolean, default: false }
})

const visible = ref(false)
const input = ref('')
const loading = ref(false)
const messages = ref([])
const listRef = ref(null)
const sessionId = ref(newSessionId())
// 中文输入法选词时按回车不能触发发送
const composing = ref(false)

// 每次发送自增，用来丢弃已中止流的迟到回调（reset / 组件卸载 / 切换仓库）
let streamSeq = 0
let activeStream = null

// 打字机节奏：SSE 一次可能送 2 个字也可能送 10 个字，
// 这里按固定节奏从缓冲里取字，避免整块文字突然跳出来
const TYPE_TICK_MS = 30 // 约 30ms 推进一拍
const TYPE_BASE_CHARS = 2 // 每拍基础字数（约 66 字/秒）
const TYPE_CATCHUP_EVERY = 60 // 缓冲每多 60 字，每拍多推进 1 字
const TYPE_MAX_CHARS = 24 // 单拍上限，长代码块也能在 1~2 秒内追平

/**
 * 仓库选择：传了 repoOptions 时用下拉里的选择，否则沿用 props 上的 owner/repo。
 * 这样仓库详情 / PR 详情 / 提交详情的旧用法完全不受影响。
 */
const repoChoices = computed(() =>
  (props.repoOptions || [])
    .filter((item) => item?.ownerName && item?.name)
    .map((item) => ({
      owner: item.ownerName,
      repo: item.name,
      key: `${item.ownerName}/${item.name}`
    }))
)

const selectable = computed(() => repoChoices.value.length > 0)
const selectedKey = ref('')

const selectedRepo = computed(
  () => repoChoices.value.find((item) => item.key === selectedKey.value) || null
)
const effectiveOwner = computed(() => selectedRepo.value?.owner || props.owner)
const effectiveRepo = computed(() => selectedRepo.value?.repo || props.repo)
const hasRepo = computed(() => Boolean(effectiveOwner.value && effectiveRepo.value))

// 仓库列表翻页/搜索后，旧选择可能已不在选项里，此时改选第一个
watch(
  repoChoices,
  (choices) => {
    if (!choices.length) {
      selectedKey.value = ''
      return
    }
    if (choices.some((item) => item.key === selectedKey.value)) return
    const fromProps = choices.find(
      (item) => item.owner === props.owner && item.repo === props.repo
    )
    selectedKey.value = (fromProps || choices[0]).key
  },
  { immediate: true }
)

const isPr = computed(() => props.prId !== null && props.prId !== '')

const prompts = computed(() =>
  isPr.value
    ? [
        '审查这个 PR 的改动，分析影响范围和历史教训',
        '解释这个 PR 改了什么',
        '这次改动符合仓库现有规范吗？',
        '这个改动可能引入哪些问题？'
      ]
    : [
        '这个仓库里鉴权相关的代码在哪？',
        '解释一下项目里 PR 合并的流程',
        '最近哪些提交改过路由或接口？'
      ]
)

// 开场白占位消息不进消息流，空态单独渲染成居中的欢迎区
const renderedMessages = computed(() =>
  messages.value
    .filter((message) => !message.intro)
    .map((message) => ({
      ...message,
      html: message.role === 'assistant' ? renderMarkdown(message.content) : ''
    }))
)

const showWelcome = computed(() => messages.value.length <= 1)

const intro = computed(() => {
  if (isPr.value) {
    return `我会基于 PR #${props.prId} 的分支差异回答问题。可以让我审查、解释或评估影响范围。`
  }
  if (selectable.value && !hasRepo.value) {
    return '请先在顶部选择一个仓库，然后可以让我解释代码、审查改动或检索仓库内容。'
  }
  return '我可以解释代码、审查改动、检索仓库内容，或根据提交历史讲述某段代码的设计原因。'
})

function newSessionId() {
  return globalThis.crypto?.randomUUID?.() ||
    `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 用户点「停止」：中止请求，并把已收到的内容显示完后收尾 */
function stopStream() {
  activeStream?.stop()
}

/** reset / 卸载：直接丢弃当前流，不再改动消息内容 */
function cancelStream() {
  streamSeq += 1
  if (!activeStream) return
  activeStream.discard()
  activeStream = null
  loading.value = false
}

function reset() {
  cancelStream()
  sessionId.value = newSessionId()
  messages.value = [{ id: 1, role: 'assistant', content: intro.value, intro: true }]
  input.value = ''
}

function open() {
  if (!messages.value.length) reset()
  visible.value = true
  scrollToBottom()
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function onEnter(event) {
  if (composing.value || event.isComposing) return
  event.preventDefault()
  send()
}

function isNearBottom(el, threshold = 80) {
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < threshold
}

async function send(text) {
  const typed = (typeof text === 'string' ? text : input.value).trim()
  if (!hasRepo.value || !typed || loading.value) return

  const isFirstUserMessage = !messages.value.some((m) => m.role === 'user')
  const query = isFirstUserMessage && props.contextText
    ? `${typed}\n\n[当前页面上下文]\n${props.contextText}`
    : typed

  messages.value.push({ id: Date.now(), role: 'user', content: typed })
  input.value = ''
  loading.value = true

  const seq = ++streamSeq
  const controller = new AbortController()

  // 先放一个空的 AI 占位消息，token 到了就往里追加
  messages.value.push({
    id: Date.now() + 1,
    role: 'assistant',
    content: '',
    streaming: true
  })
  const index = messages.value.length - 1
  scrollToBottom()

  const stream = {
    pending: '',
    timer: null,
    closed: false,
    finished: false,

    /** 收到增量：先进缓冲，由 pump 匀速吐到界面上 */
    append(token) {
      if (this.finished || !token) return
      this.pending += token
      this.schedule()
    },

    schedule() {
      if (this.timer === null && !this.finished) {
        this.timer = setTimeout(() => this.pump(), TYPE_TICK_MS)
      }
    },

    pump() {
      this.timer = null
      if (this.finished) return
      if (this.pending) {
        const take = Math.min(
          this.pending.length,
          TYPE_MAX_CHARS,
          TYPE_BASE_CHARS + Math.floor(this.pending.length / TYPE_CATCHUP_EVERY)
        )
        this.shift(take)
      }
      if (this.pending) this.schedule()
      else if (this.closed) this.finish()
    },

    /** 把缓冲开头的前 count 个字写入消息 */
    shift(count) {
      const message = messages.value[index]
      if (!message) {
        this.pending = ''
        return
      }
      const follow = isNearBottom(listRef.value)
      message.content += this.pending.slice(0, count)
      this.pending = this.pending.slice(count)
      if (follow) scrollToBottom()
    },

    /** 一次性写完缓冲（停止 / 出错 / 正常结束时用，避免残留未显示的文字） */
    flushAll() {
      if (this.pending) this.shift(this.pending.length)
    },

    finish(extra) {
      if (this.finished) return
      this.finished = true
      if (this.timer !== null) {
        clearTimeout(this.timer)
        this.timer = null
      }
      this.flushAll()
      if (seq !== streamSeq) return
      const message = messages.value[index]
      if (message) {
        message.streaming = false
        if (extra) {
          message.content = message.content ? `${message.content}\n\n${extra}` : extra
        }
        if (!message.content.trim()) message.content = '（AI 没有返回内容）'
      }
      loading.value = false
      if (activeStream === this) activeStream = null
      scrollToBottom()
    },

    /** 用户主动停止：不再等后续 SSE，显示完已收到内容后收尾 */
    stop() {
      if (this.finished) return
      this.closed = true
      controller.abort()
      this.finish('（已停止生成）')
    },

    discard() {
      this.finished = true
      this.closed = true
      if (this.timer !== null) {
        clearTimeout(this.timer)
        this.timer = null
      }
      controller.abort()
    }
  }
  activeStream = stream

  try {
    const payload = { session_id: sessionId.value, query }
    if (isPr.value) payload.id = Number(props.prId)
    await chatWithAIStream(effectiveOwner.value, effectiveRepo.value, payload, {
      signal: controller.signal,
      onToken: (token) => stream.append(token)
    })
    stream.closed = true
    // 剩余缓冲继续按节奏吐完再收尾，避免最后一大段瞬间出现
    if (!stream.pending) stream.finish()
  } catch (error) {
    stream.closed = true
    if (stream.finished) return
    if (error?.name === 'AbortError') {
      stream.finish('（已停止生成）')
    } else {
      const detail = error?.message || '未知错误'
      const hasContent = Boolean(messages.value[index]?.content || stream.pending)
      stream.finish(hasContent ? `（生成中断：${detail}）` : `请求失败：${detail}`)
    }
  }
}

onBeforeUnmount(cancelStream)

// 切换仓库 / PR 时旧会话没有意义，重置（reset 会中止仍在输出的流）
watch(
  () => [effectiveOwner.value, effectiveRepo.value, props.prId],
  () => {
    if (messages.value.length) reset()
  }
)
</script>

<template>
  <el-button
    type="primary"
    plain
    class="ai-trigger"
    :class="{ 'is-block': block }"
    @click="open"
  >
    <el-icon><ChatDotRound /></el-icon>
    <span class="btn-text">{{ label }}</span>
  </el-button>

  <el-drawer
    v-model="visible"
    :title="isPr ? `AI 代码助手 · PR #${prId}` : 'AI 代码助手'"
    size="520px"
    :append-to-body="true"
    class="ai-drawer"
  >
    <div class="assistant">
      <div class="assistant-bar">
        <el-select
          v-if="selectable"
          v-model="selectedKey"
          class="repo-select"
          size="small"
          placeholder="选择仓库"
        >
          <el-option v-for="item in repoChoices" :key="item.key" :label="item.key" :value="item.key" />
        </el-select>
        <span v-else class="muted">{{ owner }}/{{ repo }}</span>
        <el-button size="small" text @click="reset">新会话</el-button>
      </div>

      <div ref="listRef" class="messages" :class="{ 'is-welcome': showWelcome }">
        <div v-if="showWelcome" class="welcome">
          <span class="welcome-icon" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="4" y="8" width="16" height="11" rx="3" />
              <path d="M12 8V5.5" />
              <circle cx="12" cy="4" r="1.3" fill="currentColor" stroke="none" />
              <circle cx="9" cy="13" r="1.2" fill="currentColor" stroke="none" />
              <circle cx="15" cy="13" r="1.2" fill="currentColor" stroke="none" />
              <path d="M2 12.5v3M22 12.5v3" />
            </svg>
          </span>
          <h3>AI 代码助手</h3>
          <p class="welcome-copy">{{ intro }}</p>
          <div v-if="hasRepo" class="prompts">
            <el-tag
              v-for="prompt in prompts"
              :key="prompt"
              class="prompt"
              effect="plain"
              @click="send(prompt)"
            >
              {{ prompt }}
            </el-tag>
          </div>
        </div>

        <template v-else>
          <div
            v-for="message in renderedMessages"
            :key="message.id"
            class="message"
            :class="message.role === 'user' ? 'user' : 'ai'"
          >
            <UserAvatar v-if="message.role === 'user'" :size="26" class="message-avatar" />
            <BrandMark v-else :size="26" class="message-avatar" />
            <div class="message-main">
              <pre v-if="message.role === 'user'" class="content">{{ message.content }}</pre>
              <pre v-else-if="!message.content" class="content pending">{{ message.streaming ? '正在思考，可能需要几十秒…' : '（AI 没有返回内容）' }}</pre>
              <div
                v-else
                class="content md-body"
                :class="{ streaming: message.streaming }"
                v-html="message.html"
              />
            </div>
          </div>
        </template>
      </div>

      <div class="composer">
        <el-input
          v-model="input"
          class="composer-input"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          resize="none"
          :disabled="!hasRepo"
          :placeholder="hasRepo ? '描述你想了解的内容。Enter 发送，Shift+Enter 换行' : '请先在上方选择仓库'"
          @keydown.enter.exact="onEnter"
          @compositionstart="composing = true"
          @compositionend="composing = false"
        />
        <el-button
          v-if="!loading"
          class="send-btn"
          type="primary"
          :disabled="!input.trim() || !hasRepo"
          aria-label="发送"
          @click="send()"
        >
          <el-icon><Promotion /></el-icon>
        </el-button>
        <el-button
          v-else
          class="send-btn stop-btn"
          type="danger"
          plain
          aria-label="停止生成"
          title="停止生成"
          @click="stopStream"
        >
          <el-icon><VideoPause /></el-icon>
        </el-button>
      </div>
      <div class="muted hint">回答按 Markdown 渲染，代码块可横向滚动查看。</div>
    </div>
  </el-drawer>
</template>

<style scoped>
/* AI 是辅助入口：白底 + 细描边 + 靛蓝文字，不和主操作抢视觉焦点 */
.ai-trigger.el-button.is-plain {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--line-strong);
  background: var(--surface-1);
  color: var(--accent-1);
  box-shadow: 0 1px 2px oklch(0.3 0.02 260 / 0.05);
}

.ai-trigger.el-button.is-plain:hover {
  border-color: oklch(0.54 0.19 274 / 0.42);
  background: var(--hover-1);
  color: var(--accent-1);
}

/* 深色下不用蓝色底，深灰底 + 浅色文字即可 */
html.dark .ai-trigger.el-button.is-plain {
  border-color: var(--line-strong);
  background: var(--hover-2);
  color: var(--text-0);
}

html.dark .ai-trigger.el-button.is-plain:hover {
  border-color: oklch(0.64 0.17 274 / 0.45);
  background: var(--hover-2);
  color: var(--text-0);
}

.btn-text {
  margin-left: 4px;
}

.is-block {
  width: 100%;
}

.repo-select {
  flex: 1 1 auto;
  min-width: 0;
  max-width: 260px;
}

.assistant {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  /* 只允许消息区滚动，内容不会溢出到抽屉 body 上触发祖先滚动 */
  overflow: hidden;
}

.assistant-bar,
.prompts,
.composer,
.hint {
  /* 抽屉高度不足时只压缩消息区，输入区保持原样 */
  flex-shrink: 0;
}

.assistant-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  /* 滚到边界后不要把滚动链传给抽屉 body / 页面 */
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 12px 0;
}

.messages.is-welcome {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px 8px 30px;
}

.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  max-width: 420px;
  text-align: center;
}

.welcome-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 54px;
  height: 54px;
  border-radius: 16px;
  background: var(--grad-ai);
  color: #fff;
  box-shadow: 0 10px 26px -12px oklch(0.54 0.19 274 / 0.6);
}

.welcome-icon svg {
  width: 28px;
  height: 28px;
}

.welcome h3 {
  margin: 2px 0 0;
  color: var(--text-0);
  font-size: 16px;
  font-weight: 650;
}

.welcome-copy {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.7;
}

.welcome .prompts {
  justify-content: center;
  margin-top: 4px;
  padding-bottom: 0;
}

/* 头像固定在左（AI）或右（用户），内容区自适应剩余宽度 */
.message {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 8px;
  animation: msg-in 0.32s var(--ease-spring) both;
}

.message + .message {
  margin-top: 14px;
}

.message.user {
  flex-direction: row-reverse;
}

.message-avatar {
  margin-top: 2px;
}

.message-main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.message.user .message-main {
  align-items: flex-end;
}

.message.user .content {
  background: var(--hover-2);
}

@keyframes msg-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.content {
  margin: 0;
  padding: 11px 14px;
  border: 0;
  border-radius: var(--r-md);
  background: var(--hover-1);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.65;
  max-width: 100%;
}

.content.pending {
  color: var(--text-2);
  background: linear-gradient(
    100deg,
    var(--surface-2) 20%,
    rgba(99, 102, 241, 0.18) 40%,
    var(--surface-2) 60%
  );
  background-size: 220% 100%;
  animation: pending-shimmer 2.2s linear infinite;
}

@keyframes pending-shimmer {
  from {
    background-position: 120% 0;
  }
  to {
    background-position: -120% 0;
  }
}

.md-body {
  white-space: normal;
}

/* 生成中的光标，纯装饰，不进入消息文本 */
.md-body.streaming::after {
  content: '▍';
  margin-left: 1px;
  color: var(--accent-2);
  text-shadow: 0 0 10px rgba(122, 162, 247, 0.6);
  animation: caret-blink 1s steps(1) infinite;
}

@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}

.md-body :deep(p) {
  margin: 0 0 8px;
}

.md-body :deep(p:last-child) {
  margin-bottom: 0;
}

.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 12px 0 6px;
  font-size: 14px;
  line-height: 1.4;
}

.md-body :deep(h1:first-child),
.md-body :deep(h2:first-child),
.md-body :deep(h3:first-child) {
  margin-top: 0;
}

.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}

.md-body :deep(li) {
  margin: 2px 0;
}

.md-body :deep(code) {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(148, 163, 184, 0.16);
  color: var(--code-inline-text);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.md-body :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
  background: var(--code-bg);
  color: var(--code-text);
  overflow-x: auto;
}

.md-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.md-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 12px;
  border-left: 3px solid var(--accent-1);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: rgba(99, 102, 241, 0.09);
  color: var(--text-2);
}

.md-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 12px;
  border-radius: var(--r-sm);
  overflow: hidden;
}

.md-body :deep(th),
.md-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--line);
  text-align: left;
}

.md-body :deep(th) {
  background: rgba(148, 163, 184, 0.1);
  color: var(--text-1);
  font-weight: 600;
}

.md-body :deep(a) {
  color: var(--accent-2);
}

.md-body :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--line);
}

.prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 10px;
}

.prompt {
  --el-tag-bg-color: var(--hover-1);
  --el-tag-border-color: transparent;
  --el-tag-text-color: var(--text-2);
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--ring-soft);
  background: var(--hover-1);
  cursor: pointer;
  max-width: 100%;
  transition: transform var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.prompt:hover {
  transform: translateY(-1px);
  border-color: rgba(79, 70, 229, 0.4);
  background: rgba(79, 70, 229, 0.1);
  color: var(--text-1);
}

.composer {
  position: relative;
  padding: 6px;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: var(--board-bg);
  box-shadow: 0 1px 2px oklch(0.3 0.02 260 / 0.06);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.composer:focus-within {
  border-color: var(--focus-line);
  box-shadow: 0 1px 2px oklch(0.3 0.02 260 / 0.06);
}

.composer :deep(.el-textarea__inner) {
  padding: 8px 46px 8px 10px;
  background: transparent;
  box-shadow: none;
  border-radius: 8px;
}

.composer :deep(.el-textarea__inner::placeholder) {
  color: var(--text-3);
}

.composer :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

/* 发送按钮嵌在输入框内部右下角 */
.send-btn {
  position: absolute;
  right: 10px;
  bottom: 10px;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 10px;
}

.send-btn.el-button--primary.is-disabled {
  background: var(--hover-1);
  color: var(--text-3);
}

/* 生成中把发送键换成停止键，行为明确 */
.stop-btn {
  background: rgba(248, 113, 113, 0.14);
  border-color: rgba(248, 113, 113, 0.5);
  color: var(--accent-danger);
}

.stop-btn:hover,
.stop-btn:focus {
  background: rgba(248, 113, 113, 0.24);
  border-color: rgba(248, 113, 113, 0.7);
  color: var(--accent-danger);
}

.hint {
  font-size: 12px;
  margin-top: 8px;
  color: var(--text-3);
}
</style>
