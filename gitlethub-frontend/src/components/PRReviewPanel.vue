<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createReview, listReviews } from '../api/social'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true },
  pull: { type: Object, default: null },
  repoOwnerId: { type: [Number, String], default: '' }
})

const STATE_META = {
  APPROVED: { label: '通过', type: 'success' },
  CHANGES_REQUESTED: { label: '请求修改', type: 'danger' },
  COMMENTED: { label: '仅评论', type: 'info' }
}

const auth = useAuthStore()
const reviews = ref([])
const loading = ref(false)
const submitting = ref(false)
const state = ref(1)
const body = ref('')

const currentUserId = computed(() => auth.userId)
const isAuthor = computed(
  () => props.pull?.authorId != null && Number(props.pull.authorId) === Number(currentUserId.value)
)
const isRepoOwner = computed(
  () =>
    props.repoOwnerId != null &&
    props.repoOwnerId !== '' &&
    Number(props.repoOwnerId) === Number(currentUserId.value)
)

/** 每人只保留最新一条：后端按 create_time 升序返回，后写入的覆盖前面的 */
const latestByReviewer = computed(() => {
  const latest = new Map()
  for (const review of reviews.value) {
    latest.set(String(review.reviewerId), review)
  }
  return [...latest.values()]
})

const counts = computed(() => {
  const result = { APPROVED: 0, CHANGES_REQUESTED: 0, COMMENTED: 0 }
  for (const review of latestByReviewer.value) {
    if (result[review.state] !== undefined) result[review.state] += 1
  }
  return result
})

const overall = computed(() => {
  if (counts.value.CHANGES_REQUESTED > 0) {
    return { label: '存在请求修改', type: 'danger' }
  }
  if (counts.value.APPROVED > 0) {
    return { label: '已获得批准', type: 'success' }
  }
  return { label: '待评审', type: 'info' }
})

function metaOf(value) {
  return STATE_META[value] || { label: value || '未知', type: 'info' }
}

async function load() {
  loading.value = true
  try {
    reviews.value = (await listReviews(props.owner, props.repo, props.prId)) || []
  } catch {
    reviews.value = []
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!isAuthor.value && Number(state.value) === 2 && !body.value.trim()) {
    ElMessage.warning('请求修改时请填写原因，方便作者跟进')
    return
  }
  submitting.value = true
  try {
    await createReview(props.owner, props.repo, props.prId, {
      state: Number(state.value),
      body: body.value.trim(),
      commitSha: null
    })
    body.value = ''
    state.value = 1
    await load()
    ElMessage.success('评审已提交')
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.prId,
  () => {
    body.value = ''
    state.value = 1
    load()
  }
)

onMounted(load)
</script>

<template>
  <div class="review-panel">
    <div class="summary">
      <div class="summary-item">
        <span class="num ok">{{ counts.APPROVED }}</span>
        <span class="muted">通过</span>
      </div>
      <div class="summary-item">
        <span class="num danger">{{ counts.CHANGES_REQUESTED }}</span>
        <span class="muted">请求修改</span>
      </div>
      <div class="summary-item">
        <span class="num">{{ counts.COMMENTED }}</span>
        <span class="muted">仅评论</span>
      </div>
      <el-tag class="overall" :type="overall.type" effect="dark">{{ overall.label }}</el-tag>
      <el-button text size="small" :loading="loading" class="refresh" @click="load">刷新</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="保护分支的合并门禁"
      description="目标分支受保护时，至少需要一条“通过”，且不能存在“请求修改”，才能合并。同一评审人多次提交时只取最新一条。"
    />

    <div class="timeline" v-loading="loading">
      <el-empty v-if="!loading && !reviews.length" description="还没有评审记录" />
      <div v-for="review in reviews" :key="review.id" class="review-item">
        <div class="review-dot" :class="metaOf(review.state).type" />
        <div class="review-main">
          <div class="review-head">
            <span class="reviewer">{{ review.reviewerName || '未知用户' }}</span>
            <el-tag size="small" :type="metaOf(review.state).type" effect="plain">
              {{ metaOf(review.state).label }}
            </el-tag>
            <span class="muted time">{{ formatDateTime(review.createTime) }}</span>
          </div>
          <div v-if="review.body" class="review-body">{{ review.body }}</div>
          <div v-else class="muted review-body empty">（未填写说明）</div>
        </div>
      </div>
    </div>

    <div v-if="isAuthor" class="author-hint muted">
      <template v-if="isRepoOwner">
        你是这个 PR 的作者，不能评审自己的 PR；作为仓库所有者，该 PR 合并时不受评审门禁约束，可直接合并。
      </template>
      <template v-else>
        你是这个 PR 的作者，不能评审自己创建的 PR。可以请协作者提交评审。
      </template>
    </div>

    <div v-else class="review-form">
      <div class="form-title">提交评审</div>
      <el-radio-group v-model="state" size="small" class="state-group">
        <el-radio-button :value="1">通过</el-radio-button>
        <el-radio-button :value="2">请求修改</el-radio-button>
        <el-radio-button :value="0">仅评论</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="body"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 8 }"
        resize="none"
        :placeholder="Number(state) === 2 ? '说明需要修改什么（必填）' : '补充评审说明（可选）'"
      />
      <div class="form-actions">
        <span class="muted hint">评审会出现在时间线里，合并时以最新一条为准</span>
        <el-button type="primary" :loading="submitting" @click="submit">提交评审</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.review-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-gradient);
}

.summary-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.num {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}

.num.ok {
  color: var(--accent-ok);
}

.num.danger {
  color: var(--accent-danger);
}

.overall {
  margin-left: auto;
}

.refresh {
  flex-shrink: 0;
}

.timeline {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 0 4px 6px;
  min-height: 40px;
}

.review-item {
  display: flex;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}

.review-item:last-child {
  border-bottom: none;
}

.review-dot {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--text-3);
  box-shadow: 0 0 10px rgba(148, 163, 184, 0.5);
}

.review-dot.success {
  background: var(--accent-ok);
  box-shadow: 0 0 12px rgba(52, 211, 153, 0.7);
}

.review-dot.danger {
  background: var(--accent-danger);
  box-shadow: 0 0 12px rgba(248, 113, 113, 0.7);
}

.review-dot.info {
  background: var(--accent-2);
  box-shadow: 0 0 12px rgba(34, 211, 238, 0.6);
}

.review-main {
  flex: 1;
  min-width: 0;
}

.review-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.reviewer {
  font-weight: 600;
}

.time {
  font-size: 12px;
  margin-left: auto;
}

.review-body {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.review-body.empty {
  font-size: 12px;
}

.review-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-inset);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.review-form:focus-within {
  border-color: var(--focus-line);
  box-shadow: none;
}

.form-title {
  font-weight: 600;
}

.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hint {
  font-size: 12px;
  color: var(--text-3);
}

.author-hint {
  padding: 12px 14px;
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
  font-size: 13px;
  background: rgba(148, 163, 184, 0.05);
}

@media (max-width: 560px) {
  .overall {
    margin-left: 0;
  }

  .refresh {
    margin-left: auto;
  }
}
</style>
