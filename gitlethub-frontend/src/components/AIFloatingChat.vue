<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { listRepos } from '../api/repo'
import { chatWithAIStream } from '../api/ai'
import MarkdownBlock from './MarkdownBlock.vue'
import BrandMark from './BrandMark.vue'
import UserAvatar from './UserAvatar.vue'

const auth = useAuthStore()
const route = useRoute()

const MODES = [
  { value: 'free', label: '自由' },
  { value: 'review', label: '代码审查' },
  { value: 'search', label: '语义化搜索' },
  { value: 'archaeology', label: '代码考古' },
  { value: 'explain', label: '代码解释' }
]

const prompts = [
  '这个仓库里鉴权相关的代码在哪？',
  '解释一下项目里 PR 合并的流程',
  '最近哪些提交改过路由或接口？'
]

const open = ref(false)
const panelRef = ref(null)
const listRef = ref(null)
const inputRef = ref(null)

const repos = ref([])
const reposLoading = ref(false)
let reposLoaded = false

const selectedKey = ref('')
const mode = ref('free')

const repoChoices = computed(() =>
  repos.value
    .filter((item) => item?.ownerName && item?.name)
    .map((item) => ({
      key: `${item.ownerName}/${item.name}`,
      owner: item.ownerName,
      repo: item.name
    }))
)

const selectedRepo = computed(
  () => repoChoices.value.find((item) => item.key === selectedKey.value) || null
)
const ready = computed(() => Boolean(selectedRepo.value))
const activeMode = computed(() => MODES.find((item) => item.value === mode.value) || MODES[0])

const input = ref('')
const loading = ref(false)
const messages = ref([])
const sessionId = ref(newSessionId())
// 中文输入法选词时按回车不能触发发送
const composing = ref(false)

let streamSeq = 0
let activeController = null
let scrollQueued = false

const placeholder = computed(() =>
  ready.value ? '问点什么，Enter 发送，Shift+Enter 换行' : '请先选择仓库'
)

const intro = computed(() => {
  if (!repoChoices.value.length) return '还没有可用的仓库，先创建一个仓库再回来找我。'
  if (!ready.value) return '选择仓库后，我可以解释代码、审查改动或检索仓库内容。'
  return '我可以解释代码、审查改动、检索仓库内容，或根据提交历史讲述某段代码的设计原因。'
})

function newSessionId() {
  return (
    globalThis.crypto?.randomUUID?.() ||
    `sess-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  )
}

/* ---------- 拖动 ---------- */

const pos = ref(null)
const dragging = ref(false)
let dragState = null

const panelStyle = computed(() =>
  pos.value ? { left: `${pos.value.x}px`, top: `${pos.value.y}px` } : null
)

function startDrag(event) {
  // 标题栏上的按钮不参与拖动
  if (event.button !== 0 || event.target.closest('button')) return
  const rect = panelRef.value?.getBoundingClientRect()
  if (!rect) return
  dragState = {
    dx: event.clientX - rect.left,
    dy: event.clientY - rect.top,
    w: rect.width,
    h: rect.height
  }
  pos.value = { x: rect.left, y: rect.top }
  dragging.value = true
  event.preventDefault()
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', endDrag)
}

function onDragMove(event) {
  if (!dragState) return
  const maxX = Math.max(8, window.innerWidth - dragState.w - 8)
  const maxY = Math.max(8, window.innerHeight - dragState.h - 8)
  pos.value = {
    x: Math.min(Math.max(8, event.clientX - dragState.dx), maxX),
    y: Math.min(Math.max(8, event.clientY - dragState.dy), maxY)
  }
}

function endDrag() {
  dragging.value = false
  dragState = null
  window.removeEventListener('pointermove', onDragMove)
  window.removeEventListener('pointerup', endDrag)
}

function clampPos() {
  if (!pos.value || !panelRef.value) return
  const rect = panelRef.value.getBoundingClientRect()
  pos.value = {
    x: Math.max(8, Math.min(pos.value.x, window.innerWidth - rect.width - 8)),
    y: Math.max(8, Math.min(pos.value.y, window.innerHeight - rect.height - 8))
  }
}

/* ---------- 打开 / 仓库 ---------- */

async function toggle() {
  if (open.value) {
    close()
    return
  }
  open.value = true
  loadRepos()
  await nextTick()
  scrollToBottom()
  inputRef.value?.focus?.()
}

function close() {
  open.value = false
  endDrag()
}

async function loadRepos() {
  if (!reposLoaded) {
    if (!auth.username) {
      syncSelection()
      return
    }
    reposLoaded = true
    reposLoading.value = true
    try {
      const rows = (await listRepos({ owner: auth.username, keyword: '', page: 1, size: 100 })) || []
      repos.value = rows.filter((row) => row?.ownerName && row?.name)
    } catch {
      repos.value = []
      reposLoaded = false
    } finally {
      reposLoading.value = false
    }
  }
  syncSelection()
}

/** 当前页面所在的仓库优先，否则落到第一个 */
function syncSelection() {
  const routeOwner = route.params.owner
  const routeRepo = route.params.repo
  const routeKey = routeOwner && routeRepo ? `${routeOwner}/${routeRepo}` : ''

  if (routeKey && !repoChoices.value.some((item) => item.key === routeKey)) {
    repos.value.push({ ownerName: routeOwner, name: routeRepo })
  }
  if (repoChoices.value.some((item) => item.key === selectedKey.value)) return
  selectedKey.value = repoChoices.value.some((item) => item.key === routeKey)
    ? routeKey
    : repoChoices.value[0]?.key || ''
}

/* ---------- 对话 ---------- */

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
}

function stop() {
  activeController?.abort()
}

watch(selectedKey, () => {
  if (messages.value.length || loading.value) reset()
})

async function send(text) {
  const typed = (typeof text === 'string' ? text : input.value).trim()
  if (!ready.value || !typed || loading.value) return

  const modeLabel = activeMode.value.value === 'free' ? '' : activeMode.value.label
  const query = modeLabel ? `这是一个${modeLabel}\n\n${typed}` : typed

  messages.value.push({ id: Date.now(), role: 'user', content: typed, modeLabel })
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

function onWindowKeydown(event) {
  if (event.key === 'Escape' && open.value) close()
}

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
  window.addEventListener('resize', clampPos)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  window.removeEventListener('resize', clampPos)
  endDrag()
  cancelStream()
})
</script>

<template>
  <button
    type="button"
    class="ai-icon-btn"
    :class="{ 'is-active': open }"
    title="AI 助手"
    aria-label="AI 助手"
    @click="toggle"
  >
    <svg class="ai-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <defs>
        <linearGradient
          id="aiTriggerGrad"
          x1="20"
          y1="2"
          x2="4"
          y2="22"
          gradientUnits="userSpaceOnUse"
        >
          <stop offset="0" stop-color="var(--ai-grad-a)" />
          <stop offset="0.55" stop-color="var(--ai-grad-b)" />
          <stop offset="1" stop-color="var(--ai-grad-c)" />
        </linearGradient>
      </defs>
      <path
        class="ai-star-main"
        d="M11.5 2.6c.62 3.6 2.4 5.38 6 6-3.6.62-5.38 2.4-6 6-.62-3.6-2.4-5.38-6-6 3.6-.62 5.38-2.4 6-6Z"
        fill="url(#aiTriggerGrad)"
      />
      <path
        class="ai-star-mini"
        d="M18.4 14.2c.35 2.05 1.35 3.05 3.4 3.4-2.05.35-3.05 1.35-3.4 3.4-.35-2.05-1.35-3.05-3.4-3.4 2.05-.35 3.05-1.35 3.4-3.4Z"
        fill="url(#aiTriggerGrad)"
      />
    </svg>
  </button>

  <Teleport to="body">
    <transition name="ai-pop">
      <section
        v-if="open"
        ref="panelRef"
        class="ai-float"
        :class="{ 'has-pos': pos, 'is-dragging': dragging }"
        :style="panelStyle"
        role="dialog"
        aria-label="AI 助手"
      >
        <header class="ai-float-head" @pointerdown="startDrag">
          <el-icon class="ai-head-icon"><MagicStick /></el-icon>
          <span class="ai-head-title">AI 助手</span>
          <span v-if="selectedKey" class="ai-head-scope">{{ selectedKey }}</span>
          <button type="button" class="ai-head-btn ai-head-new" @click="reset">新会话</button>
          <button type="button" class="ai-head-btn ai-head-close" title="关闭" @click="close">
            <el-icon><Close /></el-icon>
          </button>
        </header>

        <div ref="listRef" class="ai-float-body" :class="{ 'is-welcome': !messages.length }">
          <div v-if="!messages.length" class="ai-welcome">
            <span class="ai-welcome-icon" aria-hidden="true">
              <el-icon><MagicStick /></el-icon>
            </span>
            <h4>AI 助手</h4>
            <p>{{ intro }}</p>
            <div v-if="ready" class="ai-prompts">
              <button
                v-for="prompt in prompts"
                :key="prompt"
                type="button"
                class="ai-prompt"
                @click="send(prompt)"
              >
                {{ prompt }}
              </button>
            </div>
          </div>

          <template v-else>
            <div
              v-for="message in messages"
              :key="message.id"
              class="ai-msg"
              :class="message.role"
            >
              <template v-if="message.role === 'user'">
                <div class="ai-msg-main">
                  <span v-if="message.modeLabel" class="ai-mode-tag">{{ message.modeLabel }}</span>
                  <span class="ai-bubble">{{ message.content }}</span>
                </div>
                <UserAvatar class="ai-user-avatar" :size="24" />
              </template>
              <template v-else>
                <BrandMark :size="24" class="ai-avatar" />
                <div class="ai-msg-main">
                  <div v-if="message.content" class="ai-ai-bubble">
                    <MarkdownBlock
                      :text="message.content"
                      :class="{ streaming: message.streaming }"
                    />
                  </div>
                  <div v-if="message.streaming" class="ai-thinking">
                    <span class="ai-spinner" aria-hidden="true"></span>
                    正在思考
                  </div>
                  <div v-else-if="!message.content" class="ai-ai-bubble pending">
                    （AI 没有返回内容）
                  </div>
                </div>
              </template>
            </div>
          </template>
        </div>

        <div class="ai-composer">
          <el-input
            ref="inputRef"
            v-model="input"
            class="ai-input"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            resize="none"
            :disabled="!ready"
            :placeholder="placeholder"
            @keydown.enter.exact="onEnter"
            @compositionstart="composing = true"
            @compositionend="composing = false"
          />

          <div class="ai-tools">
            <div class="ai-pill ai-pill-repo" :class="{ 'is-empty': !repoChoices.length }">
              <el-icon class="ai-pill-icon"><Folder /></el-icon>
              <el-select
                v-model="selectedKey"
                class="ai-select"
                size="small"
                filterable
                :loading="reposLoading"
                :placeholder="reposLoading ? '加载仓库…' : '选择仓库'"
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

            <div class="ai-pill">
              <el-icon class="ai-pill-icon"><MagicStick /></el-icon>
              <el-select v-model="mode" class="ai-select ai-select-mode" size="small" title="提问模式">
                <el-option
                  v-for="item in MODES"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </div>

            <button
              v-if="!loading"
              type="button"
              class="ai-send"
              :disabled="!input.trim() || !ready"
              title="发送"
              aria-label="发送"
              @click="send()"
            >
              <el-icon><Promotion /></el-icon>
            </button>
            <button
              v-else
              type="button"
              class="ai-send ai-stop"
              title="停止生成"
              aria-label="停止生成"
              @click="stop"
            >
              <el-icon><VideoPause /></el-icon>
            </button>
          </div>
        </div>
      </section>
    </transition>
  </Teleport>
</template>

<style scoped>
/* ---------- 顶栏触发图标 ---------- */
.ai-icon-btn {
  --ai-grad-a: #4d9bff;
  --ai-grad-b: #5b6cf0;
  --ai-grad-c: #8b5cf6;

  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  margin-right: 8px;
  padding: 0;
  border: 1px solid oklch(0.54 0.19 274 / 0.34);
  border-radius: 10px;
  background:
    radial-gradient(120% 120% at 50% 0%, oklch(0.6 0.18 285 / 0.22), transparent 72%),
    oklch(0.54 0.19 274 / 0.07);
  box-shadow: 0 1px 3px oklch(0.3 0.02 260 / 0.06), 0 0 16px -7px oklch(0.54 0.19 274 / 0.5);
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

html.dark .ai-icon-btn {
  --ai-grad-a: #7dbbff;
  --ai-grad-b: #818cf8;
  --ai-grad-c: #a78bfa;

  border-color: oklch(0.68 0.16 274 / 0.46);
  background:
    radial-gradient(120% 120% at 50% 0%, oklch(0.62 0.17 285 / 0.3), transparent 72%),
    oklch(0.54 0.19 274 / 0.1);
  box-shadow: 0 0 18px -6px oklch(0.62 0.17 285 / 0.5);
}

/* 外圈呼吸环，安静地提示这里可以点 */
.ai-icon-btn::after {
  content: '';
  position: absolute;
  inset: -3px;
  border: 1px solid oklch(0.54 0.19 274 / 0.28);
  border-radius: 13px;
  opacity: 0;
  pointer-events: none;
  animation: ai-halo 3.6s var(--ease) infinite;
}

@keyframes ai-halo {
  0% {
    opacity: 0;
    transform: scale(0.94);
  }

  30% {
    opacity: 0.85;
  }

  100% {
    opacity: 0;
    transform: scale(1.08);
  }
}

.ai-icon-btn:hover {
  border-color: oklch(0.54 0.19 274 / 0.5);
  box-shadow: 0 3px 14px -6px oklch(0.54 0.19 274 / 0.5);
}

.ai-icon-btn.is-active {
  border-color: oklch(0.54 0.19 274 / 0.55);
  background:
    radial-gradient(120% 120% at 50% 0%, oklch(0.6 0.18 285 / 0.24), transparent 72%),
    var(--hover-1);
}

.ai-icon-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--surface-1), 0 0 0 4px var(--focus-line);
}

.ai-icon {
  width: 19px;
  height: 19px;
  transition: transform var(--dur) var(--ease-spring);
}

.ai-icon-btn:hover .ai-icon {
  transform: scale(1.08) rotate(-4deg);
}

/* 两簇星芒错峰明暗，像在缓慢闪烁 */
.ai-star-main,
.ai-star-mini {
  transform-box: fill-box;
  transform-origin: center;
}

.ai-star-main {
  animation: ai-twinkle 3.2s var(--ease) infinite;
}

.ai-star-mini {
  opacity: 0.72;
  animation: ai-twinkle 3.2s var(--ease) infinite 1.1s;
}

@keyframes ai-twinkle {
  0%,
  100% {
    transform: scale(1);
    opacity: 1;
  }

  50% {
    transform: scale(0.78);
    opacity: 0.55;
  }
}

/* ---------- 浮窗 ---------- */
.ai-float {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2600;
  display: flex;
  flex-direction: column;
  width: 400px;
  height: min(560px, calc(100vh - 104px));
  border: 1px solid var(--float-border);
  border-radius: 16px;
  background: var(--board-bg);
  box-shadow: 0 24px 56px -20px oklch(0.25 0.03 265 / 0.35),
    0 6px 16px -8px oklch(0.25 0.03 265 / 0.2);
  overflow: hidden;
}

/* 拖动过之后用内联 left/top 定位，去掉默认的右下角锚点 */
.ai-float.has-pos {
  right: auto;
  bottom: auto;
}

.ai-float.is-dragging {
  box-shadow: 0 30px 64px -20px oklch(0.25 0.03 265 / 0.45),
    0 8px 20px -8px oklch(0.25 0.03 265 / 0.24);
}

.ai-pop-enter-active,
.ai-pop-leave-active {
  transition: opacity 180ms var(--ease), transform 200ms var(--ease-spring);
}

.ai-pop-enter-from,
.ai-pop-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

.ai-float-head {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 8px 0 12px;
  border-bottom: 1px solid var(--line-soft);
  cursor: grab;
  user-select: none;
  touch-action: none;
}

.ai-float.is-dragging .ai-float-head {
  cursor: grabbing;
}

.ai-head-icon {
  flex: 0 0 auto;
  color: var(--accent-2);
  font-size: 15px;
}

.ai-head-title {
  flex: 0 0 auto;
  color: var(--text-0);
  font-size: 13px;
  font-weight: 600;
}

.ai-head-scope {
  min-width: 0;
  overflow: hidden;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--hover-1);
  color: var(--text-3);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-head-btn {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  height: 26px;
  min-width: 26px;
  padding: 0 7px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-3);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background-color 120ms var(--ease), color 120ms var(--ease);
}

.ai-head-btn:hover {
  background: var(--hover-1);
  color: var(--text-0);
}

.ai-head-new {
  margin-left: auto;
  color: var(--accent-2);
}

.ai-head-close {
  padding: 0;
}

.ai-head-close .el-icon {
  font-size: 14px;
}

/* ---------- 消息区 ---------- */
.ai-float-body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 14px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.ai-float-body.is-welcome {
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.ai-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  max-width: 300px;
  text-align: center;
}

.ai-welcome-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: var(--grad-ai);
  color: #fff;
  font-size: 22px;
  box-shadow: 0 10px 24px -12px oklch(0.54 0.19 274 / 0.6);
}

.ai-welcome h4 {
  margin: 2px 0 0;
  color: var(--text-0);
  font-size: 15px;
  font-weight: 650;
}

.ai-welcome p {
  margin: 0;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.7;
}

.ai-prompts {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 7px;
  margin-top: 6px;
}

.ai-prompt {
  max-width: 100%;
  padding: 5px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--hover-1);
  color: var(--text-2);
  font: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: transform var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.ai-prompt:hover {
  transform: translateY(-1px);
  border-color: rgba(79, 70, 229, 0.4);
  background: rgba(79, 70, 229, 0.1);
  color: var(--text-1);
}

.ai-msg {
  display: flex;
  flex-direction: column;
  min-width: 0;
  animation: ai-msg-in 0.22s var(--ease) both;
}

/* 头像在 DOM 里排在内容之后，普通 row 就能落在右侧 */
.ai-msg.user,
.ai-msg.assistant {
  flex-direction: row;
  align-items: flex-start;
  gap: 8px;
}

@keyframes ai-msg-in {
  from {
    opacity: 0;
    transform: translateY(5px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

.ai-avatar,
.ai-user-avatar {
  margin-top: 2px;
}

.ai-msg-main {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: flex-start;
  max-width: calc(100% - 32px);
  min-width: 0;
}

.ai-msg.user .ai-msg-main {
  align-items: flex-end;
}

.ai-bubble {
  max-width: 85%;
  padding: 8px 13px;
  border-radius: 14px 14px 4px 14px;
  background: var(--hover-2);
  color: var(--text-1);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-ai-bubble {
  max-width: 100%;
  padding: 9px 13px;
  border-radius: 4px 14px 14px 14px;
  background: var(--ai-bubble-bg);
  color: var(--text-1);
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
}

.ai-ai-bubble.pending {
  color: var(--text-3);
}

.ai-ai-bubble :deep(.md-body.streaming)::after {
  content: '▍';
  margin-left: 1px;
  color: var(--accent-2);
  animation: ai-caret 1s steps(1) infinite;
}

@keyframes ai-caret {
  50% {
    opacity: 0;
  }
}

.ai-thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  color: var(--text-3);
  font-size: 12px;
}

.ai-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--line-strong);
  border-top-color: var(--accent-2);
  border-radius: 50%;
  animation: ai-spin 0.7s linear infinite;
}

@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}

.ai-mode-tag {
  margin-bottom: 4px;
  padding: 1px 8px;
  border: 1px solid oklch(0.54 0.19 274 / 0.24);
  border-radius: 999px;
  background: oklch(0.54 0.19 274 / 0.08);
  color: var(--accent-2);
  font-size: 11px;
  line-height: 17px;
}

/* ---------- 输入区 ---------- */
.ai-composer {
  flex: 0 0 auto;
  padding: 6px;
  border-top: 1px solid var(--line-soft);
}

.ai-input {
  width: 100%;
}

.ai-input :deep(.el-textarea__inner),
.ai-input :deep(.el-textarea__inner:hover),
.ai-input :deep(.el-textarea__inner:focus),
.ai-input :deep(.el-textarea__inner:focus-visible) {
  padding: 6px 8px 4px;
  border-radius: 10px;
  background: transparent;
  box-shadow: none;
  outline: none !important;
  color: var(--text-0);
  font-size: 13px;
  line-height: 1.6;
}

.ai-input :deep(.el-textarea__inner::placeholder) {
  color: var(--text-3);
}

.ai-tools {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 2px 2px;
}

/* 仓库 / 模式：左下角的浅灰胶囊，明确是可点击的选择器 */
.ai-pill {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 7px 0 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-1);
  transition: background-color 120ms var(--ease), border-color 120ms var(--ease);
}

.ai-pill:hover {
  border-color: var(--line-strong);
  background: var(--hover-2);
}

.ai-pill-repo {
  gap: 5px;
  width: 158px;
}

.ai-pill.is-empty {
  opacity: 0.7;
}

.ai-pill-icon {
  flex: 0 0 auto;
  color: var(--text-2);
  font-size: 13px;
}

.ai-select {
  flex: 1 1 auto;
  min-width: 0;
}

.ai-select :deep(.el-select__wrapper),
.ai-select :deep(.el-select__wrapper:hover),
.ai-select :deep(.el-select__wrapper.is-focused),
.ai-select :deep(.el-select__wrapper.is-hovering) {
  min-height: 22px;
  padding: 0;
  background: transparent;
  box-shadow: none !important;
  outline: none !important;
}

.ai-select :deep(.el-select__placeholder) {
  color: var(--text-0);
  font-size: 12px;
  font-weight: 500;
}

.ai-select :deep(.el-select__placeholder.is-transparent) {
  color: var(--text-2);
  font-weight: 400;
}

.ai-select :deep(.el-select__caret) {
  color: var(--text-2);
  font-size: 12px;
}

.ai-select-mode {
  flex: 0 0 auto;
  width: 76px;
}

.ai-send {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  margin-left: auto;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: var(--accent-1);
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  box-shadow: 0 2px 10px oklch(0.54 0.19 274 / 0.28);
  transition: background-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    opacity var(--dur) var(--ease);
}

.ai-send:hover {
  filter: brightness(0.93);
}

.ai-send:disabled {
  background: var(--hover-1);
  color: var(--text-3);
  cursor: default;
  box-shadow: none;
  filter: none;
}

/* 深色下不做发光蓝块，跟全局主按钮保持一致 */
html.dark .ai-send {
  background: oklch(0.27 0.025 265);
  border: 1px solid oklch(0.64 0.17 274 / 0.34);
  color: var(--text-0);
  box-shadow: none;
}

html.dark .ai-send:hover {
  background: oklch(0.32 0.035 268);
  border-color: oklch(0.68 0.16 274 / 0.55);
  color: #fff;
  filter: none;
}

html.dark .ai-send:disabled {
  background: oklch(0.24 0.02 265);
  border-color: var(--line);
  color: var(--text-3);
}

.ai-stop {
  background: rgba(248, 113, 113, 0.16);
  border: 1px solid rgba(248, 113, 113, 0.5);
  color: var(--accent-danger);
  box-shadow: none;
}

.ai-stop:hover {
  background: rgba(248, 113, 113, 0.24);
  border-color: rgba(248, 113, 113, 0.7);
  color: var(--accent-danger);
  filter: none;
}

@media (max-width: 560px) {
  .ai-float {
    right: 12px;
    bottom: 12px;
    width: calc(100vw - 24px);
    height: min(560px, calc(100vh - 88px));
  }

  .ai-pill-repo {
    width: 128px;
  }

  .ai-select-mode {
    width: 68px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-pop-enter-active,
  .ai-pop-leave-active,
  .ai-msg,
  .ai-icon {
    transition: none;
    animation: none;
  }

  .ai-icon-btn::after,
  .ai-star-main,
  .ai-star-mini {
    animation: none;
  }

  .ai-icon-btn::after {
    display: none;
  }

  .ai-spinner {
    animation-duration: 2s;
  }
}
</style>
