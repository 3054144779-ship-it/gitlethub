<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createAiReview, getAiReview, listAiReviews } from '../api/aiReview'
import MarkdownBlock from './MarkdownBlock.vue'
import { formatDateTime, shortSha } from '../utils/format'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true }
})

const STATUS_META = {
  RUNNING: { label: '审查中', type: 'warning' },
  SUCCESS: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' }
}

const POLL_INTERVAL_MS = 4000
const HISTORY_SIZE = 5

const records = ref([])
const loading = ref(false)
const triggering = ref(false)

// setTimeout 链而不是 setInterval：慢请求不会堆积，停止时也不会漏掉在途回调
let pollTimer = null
let pollSeq = 0

const latest = computed(() => records.value[0] || null)
const history = computed(() => records.value.slice(1))

function metaOf(value) {
  return STATUS_META[value] || { label: value || '未知', type: 'info' }
}

function durationText(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

function merge(record) {
  const index = records.value.findIndex((item) => item.id === record.id)
  if (index === -1) records.value.unshift(record)
  else records.value.splice(index, 1, record)
}

function stopPoll() {
  pollSeq += 1
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(recordId) {
  stopPoll()
  const seq = pollSeq

  function tick() {
    pollTimer = null
    getAiReview(props.owner, props.repo, recordId)
      .then((record) => {
        if (seq !== pollSeq || !record) return
        merge(record)
        if (record.status === 'RUNNING') {
          pollTimer = setTimeout(tick, POLL_INTERVAL_MS)
        } else if (record.status === 'SUCCESS') {
          ElMessage.success('AI 审查已完成')
        }
      })
      .catch(() => {
        // 拦截器已经提示过错误，这里不再重试，避免失败时刷屏
        if (seq === pollSeq) pollTimer = null
      })
  }

  pollTimer = setTimeout(tick, POLL_INTERVAL_MS)
}

async function load() {
  loading.value = true
  try {
    records.value =
      (await listAiReviews(props.owner, props.repo, {
        pullRequestId: Number(props.prId),
        size: HISTORY_SIZE
      })) || []
  } catch {
    records.value = []
  } finally {
    loading.value = false
  }
  // 页面刷新回来时如果还有在跑的任务，继续轮询它的结果
  const running = records.value.find((item) => item.status === 'RUNNING')
  if (running) schedulePoll(running.id)
}

async function trigger() {
  if (triggering.value) return
  triggering.value = true
  try {
    const record = await createAiReview(props.owner, props.repo, {
      pullRequestId: Number(props.prId)
    })
    if (!record) return
    merge(record)
    if (record.status === 'RUNNING') {
      ElMessage.success('已发起 AI 审查，大约需要几十秒')
      schedulePoll(record.id)
    } else if (record.status === 'SUCCESS') {
      ElMessage.success('已复用相同改动的历史结论')
    }
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    triggering.value = false
  }
}

watch(
  () => props.prId,
  () => {
    stopPoll()
    records.value = []
    load()
  }
)

onBeforeUnmount(stopPoll)
</script>

<template>
  <div class="ai-review">
    <div class="panel-toolbar">
      <div class="title-wrap">
        <span class="title">AI 代码审查</span>
        <span class="muted hint">异步执行，结论会保留在历史记录中</span>
      </div>
      <el-button type="primary" :loading="triggering" :disabled="loading" @click="trigger">
        {{ latest ? '重新审查' : '发起审查' }}
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="基于 PR 源分支与目标分支的差异"
      description="相同 diff 命中历史成功结论时会直接复用，不重复消耗模型额度；耗时通常为几十秒。"
    />

    <div v-loading="loading" class="content">
      <el-empty v-if="!loading && !records.length" description="还没有 AI 审查记录">
        <el-button type="primary" @click="trigger">立即发起审查</el-button>
      </el-empty>

      <template v-else-if="latest">
        <div class="review-card" :class="{ running: latest.status === 'RUNNING' }">
          <div class="review-head">
            <el-tag :type="metaOf(latest.status).type" effect="dark">
              {{ metaOf(latest.status).label }}
            </el-tag>
            <span class="mono model">{{ latest.model || 'gitlet-ai' }}</span>
            <span class="muted time">{{ formatDateTime(latest.createTime) }}</span>
          </div>

          <div class="meta">
            <span class="meta-item">
              <span class="muted">提交</span>
              <span class="mono sha">{{ shortSha(latest.commitSha, 12) }}</span>
            </span>
            <span class="meta-item">
              <span class="muted">耗时</span>
              <span>{{ durationText(latest.durationMs) }}</span>
            </span>
            <span v-if="latest.updateTime" class="meta-item">
              <span class="muted">更新</span>
              <span>{{ formatDateTime(latest.updateTime) }}</span>
            </span>
          </div>

          <div v-if="latest.status === 'RUNNING'" class="running-box">
            <span class="pulse" />
            正在读取 diff 并生成审查结论，页面会自动更新…
          </div>

          <div v-else-if="latest.status === 'SUCCESS'" class="conclusion">
            <MarkdownBlock :text="latest.conclusion || '（模型没有返回结论）'" />
          </div>

          <el-alert
            v-else-if="latest.status === 'FAILED'"
            type="error"
            :closable="false"
            show-icon
            title="本次审查失败"
            :description="latest.errorMessage || '模型调用失败，可以稍后重试'"
          />

          <div v-else class="muted cancelled">该次审查{{ metaOf(latest.status).label }}</div>
        </div>

        <div v-if="history.length" class="history">
          <div class="history-title">历史记录</div>
          <el-collapse>
            <el-collapse-item v-for="item in history" :key="item.id" :name="item.id">
              <template #title>
                <div class="history-head">
                  <el-tag size="small" :type="metaOf(item.status).type" effect="plain">
                    {{ metaOf(item.status).label }}
                  </el-tag>
                  <span class="mono sha">{{ shortSha(item.commitSha, 12) }}</span>
                  <span class="muted time">{{ formatDateTime(item.createTime) }}</span>
                </div>
              </template>

              <div v-if="item.status === 'SUCCESS'" class="history-body">
                <MarkdownBlock :text="item.conclusion || '（模型没有返回结论）'" />
              </div>
              <div v-else-if="item.status === 'FAILED'" class="history-error">
                {{ item.errorMessage || '模型调用失败' }}
              </div>
              <div v-else class="muted">该次审查{{ metaOf(item.status).label }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ai-review {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.title-wrap {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.title {
  font-weight: 600;
}

.hint {
  font-size: 12px;
}

.content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 60px;
}

.review-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-gradient);
  overflow: hidden;
}

.review-card.running::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1px;
  background: linear-gradient(120deg, rgba(99, 102, 241, 0.7), rgba(34, 211, 238, 0.55), rgba(168, 85, 247, 0.65), rgba(99, 102, 241, 0.7));
  background-size: 300% 100%;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  animation: border-flow 3s linear infinite;
  pointer-events: none;
}

@keyframes border-flow {
  from {
    background-position: 0% 0;
  }
  to {
    background-position: 300% 0;
  }
}

.review-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.model {
  font-size: 12px;
  color: var(--accent-2);
}

.time {
  font-size: 12px;
  margin-left: auto;
}

.meta {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-2);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sha {
  color: var(--accent-2);
}

.running-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  color: var(--text-2);
  background: linear-gradient(
    100deg,
    var(--surface-2) 20%,
    rgba(99, 102, 241, 0.16) 40%,
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

.pulse {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--accent-2);
  box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.6);
  animation: pulse-ring 1.8s var(--ease) infinite;
}

@keyframes pulse-ring {
  70% {
    box-shadow: 0 0 0 10px rgba(34, 211, 238, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(34, 211, 238, 0);
  }
}

.conclusion {
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--code-bg);
}

.cancelled {
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  font-size: 13px;
}

.history {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.history-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}

.history-head .time {
  margin-left: auto;
}

.history-body {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--panel-inset);
}

.history-error {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--accent-danger);
  border: 1px solid rgba(248, 113, 113, 0.35);
  border-radius: var(--r-sm);
  background: rgba(248, 113, 113, 0.08);
}

@media (prefers-reduced-motion: reduce) {
  .running-box,
  .review-card.running::before,
  .pulse {
    animation: none;
  }
}
</style>
