<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Folder, MagicStick, Share } from '@element-plus/icons-vue'
import BrandMark from './BrandMark.vue'
import '../styles/auth.css'

const props = defineProps({
  // 登录 / 注册切换时递增，用来触发右侧卡片的上浮渐显
  swapKey: { type: String, default: '' }
})

const emit = defineEmits(['open'])

const FLIP_TARGETS =
  '.scene-logo, .scene-kicker, .title-line, .scene-desc, .scene-points li, .scene-foot'

const split = ref(false)
const sceneEl = ref(null)
const paneEl = ref(null)
const panelEl = ref(null)

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

// 首屏标题：逐字显示 + 光标跟随
const typing = ref(!reduceMotion)
const line1El = ref(null)
const line2El = ref(null)
const caret1El = ref(null)
const caret2El = ref(null)
const typeTimers = []
let typeRunId = 0

function wait(ms) {
  return new Promise((resolve) => {
    const id = setTimeout(() => {
      const index = typeTimers.indexOf(id)
      if (index !== -1) typeTimers.splice(index, 1)
      resolve()
    }, ms)
    typeTimers.push(id)
  })
}

// 逐字量出「从行首到第 n 个字」的宽度，用来推进裁切和光标
function measurePrefixWidths(el) {
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  if (!nodes.length) return []

  const range = document.createRange()
  const first = nodes[0]
  const widths = []

  for (const node of nodes) {
    for (let i = 1; i <= node.data.length; i++) {
      range.setStart(first, 0)
      range.setEnd(node, i)
      widths.push(range.getBoundingClientRect().width)
    }
  }

  return widths
}

function typeLine(clipEl, caretEl, runId, keepCaret = false) {
  return new Promise((resolve) => {
    if (!clipEl || !caretEl) return resolve()
    const widths = measurePrefixWidths(clipEl)
    if (!widths.length) return resolve()

    const fullWidth = clipEl.getBoundingClientRect().width
    let index = 0

    caretEl.style.left = '0px'
    caretEl.classList.add('is-visible')

    const step = () => {
      if (runId !== typeRunId) return
      const width = widths[index]
      clipEl.style.clipPath = `inset(0 ${Math.max(0, fullWidth - width)}px 0 0)`
      caretEl.style.left = `${width}px`
      index += 1

      if (index < widths.length) {
        wait(78).then(step)
      } else {
        wait(220).then(() => {
          if (runId !== typeRunId) return
          clipEl.style.clipPath = 'none'
          if (!keepCaret) caretEl.classList.remove('is-visible')
          resolve()
        })
      }
    }

    wait(180).then(step)
  })
}

async function runTypewriter() {
  if (!typing.value) return
  const runId = ++typeRunId
  await nextTick()
  await typeLine(line1El.value, caret1El.value, runId)
  if (runId !== typeRunId) return
  await wait(110)
  if (runId !== typeRunId) return
  await typeLine(line2El.value, caret2El.value, runId)
  if (runId !== typeRunId) return
  typing.value = false
}

// 打开分屏时立即收尾，避免带着半截标题和光标进入 FLIP
function finishTyping() {
  typeRunId += 1
  typeTimers.splice(0).forEach((id) => clearTimeout(id))
  typing.value = false
  if (line1El.value) line1El.value.style.clipPath = 'none'
  if (line2El.value) line2El.value.style.clipPath = 'none'
  caret1El.value?.classList.remove('is-visible')
  caret2El.value?.classList.remove('is-visible')
}

function captureFlip() {
  if (!sceneEl.value) return null
  if (!window.matchMedia('(min-width: 901px)').matches) return null
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return null

  return [...sceneEl.value.querySelectorAll(FLIP_TARGETS)].map((el) => ({
    el,
    rect: el.getBoundingClientRect()
  }))
}

function playFlip(before) {
  for (const { el, rect: first } of before) {
    const last = el.getBoundingClientRect()
    const dx = first.left - last.left
    const dy = first.top - last.top
    const sx = last.width ? first.width / last.width : 1
    const sy = last.height ? first.height / last.height : 1

    if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5 && Math.abs(sx - 1) < 0.01 && Math.abs(sy - 1) < 0.01) {
      continue
    }

    el.style.transformOrigin = 'top left'
    el.animate(
      [
        { transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
        { transform: 'none' }
      ],
      { duration: 620, easing: 'cubic-bezier(0.22, 0.61, 0.36, 1)' }
    )
  }
}

async function openSplit() {
  if (split.value) return
  finishTyping()
  const before = captureFlip()
  split.value = true
  await nextTick()
  if (before) playFlip(before)
  // 移动端不自动聚焦，避免弹出软键盘把分屏顶乱
  if (window.matchMedia('(min-width: 761px)').matches) {
    paneEl.value?.querySelector('input')?.focus()
  }
}

async function closeSplit() {
  if (!split.value) return
  const before = captureFlip()
  split.value = false
  await nextTick()
  if (before) playFlip(before)
  fadeInVisual()
}

// 切回首屏时，右侧终端和两个主按钮从淡出状态渐显回来
function fadeInVisual() {
  if (!window.matchMedia('(min-width: 901px)').matches) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const ease = 'cubic-bezier(0.22, 0.61, 0.36, 1)'
  const visual = sceneEl.value?.querySelector('.scene-visual')
  const actions = sceneEl.value?.querySelector('.scene-actions')

  if (window.matchMedia('(min-width: 1101px)').matches) {
    visual?.animate(
      [
        { opacity: 0, transform: 'translateY(48px) scale(0.92)', filter: 'blur(10px)' },
        { opacity: 1, transform: 'none', filter: 'blur(0px)' }
      ],
      { duration: 760, easing: ease }
    )
  }

  // 与标题、功能卡片的 FLIP 一致：都从左侧约 50px 的位置向右归位
  actions?.animate(
    [
      { opacity: 0, transform: 'translateX(-48px)' },
      { opacity: 1, transform: 'none' }
    ],
    // fill: backwards 让延迟阶段就保持透明，否则按钮会先正常显示再突然闪一下
    { duration: 800, delay: 300, easing: ease, fill: 'backwards' }
  )
}

function requestOpen(mode) {
  if (split.value) return
  emit('open', mode)
}

defineExpose({ open: openSplit })

// 表单在登录 / 注册之间原地切换时，让右侧卡片重新从下往上渐显
watch(
  () => props.swapKey,
  async (value, oldValue) => {
    if (!split.value || value === oldValue) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    await nextTick()
    panelEl.value?.animate(
      [
        { opacity: 0, transform: 'translateY(28px)' },
        { opacity: 1, transform: 'none' }
      ],
      { duration: 340, easing: 'cubic-bezier(0.22, 0.61, 0.36, 1)' }
    )
  }
)

function onKeydown(event) {
  if (event.key === 'Escape' && split.value) closeSplit()
}

watch(split, (value) => {
  document.body.style.overflow = value ? 'hidden' : ''
})

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  runTypewriter()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  finishTyping()
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="auth-landing" :class="{ 'is-split': split }">
    <div ref="sceneEl" class="auth-scene" :aria-hidden="split">
      <div class="scene-aurora" aria-hidden="true">
        <span class="aurora-sweep"></span>
        <span class="aurora-blob aurora-blob--x"></span>
        <span class="aurora-blob aurora-blob--y"></span>
        <span class="aurora-blob aurora-blob--diag"></span>
      </div>

      <header class="scene-head">
        <div class="scene-logo">
          <BrandMark :size="34" />
          <span>GitletHub</span>
        </div>
        <div class="scene-head-actions">
          <button type="button" class="scene-cta scene-cta--text" @click="requestOpen('login')">
            登录
          </button>
          <button type="button" class="scene-cta scene-cta--outline" @click="requestOpen('register')">
            注册
          </button>
        </div>
      </header>

      <main class="scene-main">
        <div class="scene-hero">
          <div class="scene-copy">
            <p class="scene-kicker">AI-POWERED CODE HOSTING</p>
            <h1 class="scene-title">
              <span class="title-line"><span ref="line1El" class="title-line-text" :class="{ 'is-typing': typing }"><span class="scene-title-accent">AI 驱动</span>的</span><span ref="caret1El" class="type-caret" aria-hidden="true"></span></span>
              <span class="title-line"><span ref="line2El" class="title-line-text" :class="{ 'is-typing': typing }">代码托管平台</span><span ref="caret2El" class="type-caret" aria-hidden="true"></span></span>
            </h1>
            <p class="scene-desc">仓库、评审与提交图谱集中在一个工作台，让协作链路更短。</p>

            <div class="scene-actions">
              <button type="button" class="scene-cta scene-cta--hero" @click="requestOpen('register')">
                创建账号
              </button>
              <button
                type="button"
                class="scene-cta scene-cta--hero scene-cta--ghost"
                @click="requestOpen('login')"
              >
                登录
              </button>
            </div>
          </div>

          <!-- 悬浮终端窗口：纯装饰，给首屏一个右侧的视觉锚点 -->
          <div class="scene-visual" aria-hidden="true">
            <div class="code-window">
              <div class="code-bar">
                <span class="code-dot"></span>
                <span class="code-dot"></span>
                <span class="code-dot"></span>
                <span class="code-name">gitlet — review</span>
              </div>
              <div class="code-body">
                <p><span class="code-prompt">$</span> <span class="code-cmd">gitlet push</span> origin main</p>
                <p class="code-dim">Enumerating objects: 12, done.</p>
                <p class="code-dim">Writing objects: 100% (12/12), 4.2 KiB | 1.1 MiB/s, done.</p>
                <p class="code-dim">To 100.103.241.43:huahuo/gitlet-test.git</p>
                <p class="code-dim"> * <span class="code-soft">[new branch]</span>      main -&gt; main</p>
                <p class="code-gap"></p>
                <p><span class="code-prompt">$</span> <span class="code-cmd code-cmd--hi">gitlet review --ai</span></p>
                <p><span class="code-ok">AI Reviewer:</span> 检测到 2 个潜在空指针异常...</p>
                <p class="code-line"><span class="code-prompt">$</span><span class="code-caret"></span></p>
              </div>
            </div>
          </div>
        </div>

        <ul class="scene-points">
          <li>
            <span class="point-icon"><el-icon><Folder /></el-icon></span>
            <span class="point-copy">
              <strong>仓库管理</strong>
              <small>分支、提交与成员协作</small>
            </span>
          </li>
          <li>
            <span class="point-icon"><el-icon><MagicStick /></el-icon></span>
            <span class="point-copy">
              <strong>AI 代码评审</strong>
              <small>合并前自动发现问题</small>
            </span>
          </li>
          <li>
            <span class="point-icon"><el-icon><Share /></el-icon></span>
            <span class="point-copy">
              <strong>提交图谱</strong>
              <small>一眼看清分支演化</small>
            </span>
          </li>
        </ul>
      </main>

      <p class="scene-foot">GitletHub · 让每一次提交都有据可循</p>
    </div>

    <main ref="paneEl" class="auth-pane" :inert="!split" :aria-hidden="!split">
      <button type="button" class="split-back" @click="closeSplit">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回</span>
      </button>

      <div ref="panelEl" class="auth-panel">
        <div class="pane-brand">
          <BrandMark :size="34" />
          <span>GitletHub</span>
        </div>
        <slot />
      </div>
    </main>
  </div>
</template>
