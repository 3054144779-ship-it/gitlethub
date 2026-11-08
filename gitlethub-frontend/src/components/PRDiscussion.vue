<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createComment, listComments } from '../api/social'
import { useAuthStore } from '../stores/auth'
import { commentDisplayMode } from '../utils/commentMode'
import { flattenComments } from '../utils/comments'
import CommentItem from './CommentItem.vue'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true },
  repoOwnerId: { type: [Number, String], default: '' }
})

const emit = defineEmits(['open-diff'])

const auth = useAuthStore()

const PAGE_SIZE = 10
const comments = ref([])
const total = ref(0)
const page = ref(1)
const commentType = ref('')
const loading = ref(false)
const submitting = ref(false)
const body = ref('')
const replyTo = ref(null)
const composerRef = ref(null)
const composerBox = ref(null)

const currentUserId = computed(() => auth.userId)

const displayComments = computed(() =>
  commentDisplayMode.value === 'flat'
    ? flattenComments(comments.value)
    : comments.value.map((comment) => ({ comment, replyToName: '' }))
)

async function load() {
  loading.value = true
  try {
    const data = await listComments(props.owner, props.repo, props.prId, {
      commentType: commentType.value === '' ? undefined : Number(commentType.value),
      pageNum: page.value,
      pageSize: PAGE_SIZE
    })
    comments.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch {
    comments.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function submit() {
  const text = body.value.trim()
  if (!text) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  submitting.value = true
  try {
    await createComment(props.owner, props.repo, props.prId, {
      body: text,
      commentType: commentType.value === '' ? 1 : Number(commentType.value),
      replyToId: replyTo.value?.id ?? null
    })
    const replied = Boolean(replyTo.value)
    body.value = ''
    replyTo.value = null
    // 顶层评论按时间升序分页，新评论落在最后一页；回复留在当前页，避免位置跳动
    if (!replied) page.value = 1
    await load()
    if (!replied) {
      const lastPage = Math.max(1, Math.ceil(total.value / PAGE_SIZE))
      if (lastPage !== page.value) {
        page.value = lastPage
        await load()
      }
    }
    ElMessage.success(replied ? '回复已发布' : '评论已发布')
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

async function onReply(comment) {
  replyTo.value = comment
  await nextTick()
  composerBox.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  composerRef.value?.focus?.()
}

function onPageChange(next) {
  page.value = next
  load()
}

function onFilterChange() {
  page.value = 1
  load()
}

watch(commentType, onFilterChange)
watch(
  () => props.prId,
  () => {
    page.value = 1
    replyTo.value = null
    body.value = ''
    load()
  }
)

onMounted(load)
</script>

<template>
  <div class="discussion">
    <div class="panel-toolbar">
      <el-radio-group v-model="commentType" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button :value="1">整体</el-radio-button>
        <el-radio-button :value="2">行级</el-radio-button>
      </el-radio-group>
      <span class="muted count">共 {{ total }} 条</span>
      <el-radio-group v-model="commentDisplayMode" size="small" class="mode-switch">
        <el-radio-button value="tree">树状</el-radio-button>
        <el-radio-button value="flat">平铺</el-radio-button>
      </el-radio-group>
      <el-button text size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-loading="loading" class="list">
      <el-empty v-if="!loading && !comments.length" description="还没有评论，来写下第一条吧" />
      <CommentItem
        v-for="entry in displayComments"
        :key="entry.comment.id"
        :comment="entry.comment"
        :display-mode="commentDisplayMode"
        :reply-to-name="entry.replyToName"
        :owner="owner"
        :repo="repo"
        :pr-id="prId"
        :current-user-id="currentUserId"
        :repo-owner-id="repoOwnerId"
        @reply="onReply"
        @changed="load"
      />
    </div>

    <el-pagination
      v-if="total > PAGE_SIZE"
      class="pager"
      small
      background
      layout="prev, pager, next"
      :total="total"
      :page-size="PAGE_SIZE"
      :current-page="page"
      @current-change="onPageChange"
    />

    <div v-if="commentType === 2 && !replyTo" class="inline-guide">
      <div class="guide-text">
        <b>行级评论需要挂在具体代码行上</b>
        <span class="hint">切到「变更」页，把鼠标移到任意代码行，点击左侧的 + 即可在该行评论。</span>
      </div>
      <el-button type="primary" size="small" @click="emit('open-diff')">去变更页</el-button>
    </div>

    <div v-else ref="composerBox" class="composer">
      <div v-if="replyTo" class="reply-hint">
        <span>回复 <b>@{{ replyTo.userName || '未知用户' }}</b></span>
        <el-button text size="small" @click="replyTo = null">取消</el-button>
      </div>
      <el-input
        ref="composerRef"
        v-model="body"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        resize="none"
        placeholder="写下你的评论。Enter 换行，点击右侧按钮发布"
      />
      <div class="composer-actions">
        <span class="muted hint">评论对仓库协作者可见</span>
        <el-button type="primary" :loading="submitting" @click="submit">发表评论</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.discussion {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.count {
  font-size: 12px;
}

.mode-switch {
  margin-left: auto;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 40px;
}

.pager {
  justify-content: flex-end;
}

.inline-guide {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  border: 1px dashed rgba(99, 102, 241, 0.4);
  border-radius: var(--r-md);
  background: linear-gradient(150deg, rgba(99, 102, 241, 0.12), rgba(34, 211, 238, 0.05));
}

.guide-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--text-1);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-inset);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.composer:focus-within {
  border-color: var(--focus-line);
  box-shadow: none;
}

.composer :deep(.el-textarea__inner) {
  padding: 6px 8px;
  background: transparent;
  box-shadow: none;
}

.composer :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.hint {
  font-size: 12px;
  color: var(--text-3);
}

.reply-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--text-2);
  border: 1px solid rgba(99, 102, 241, 0.35);
  border-radius: var(--r-sm);
  background: rgba(99, 102, 241, 0.1);
}
</style>
