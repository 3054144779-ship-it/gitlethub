<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteComment, updateComment } from '../api/social'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  comment: { type: Object, required: true },
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true },
  currentUserId: { type: [Number, String], default: '' },
  repoOwnerId: { type: [Number, String], default: '' },
  depth: { type: Number, default: 0 },
  /** tree=递归渲染 replies；flat=由外层展平渲染，本组件不再递归 */
  displayMode: { type: String, default: 'tree' },
  /** flat 模式下被回复人，用于渲染「回复 @某人」 */
  replyToName: { type: String, default: '' }
})

const emit = defineEmits(['reply', 'changed'])

const editing = ref(false)
const editBody = ref('')
const saving = ref(false)
const deleting = ref(false)

const isOwn = computed(
  () => props.currentUserId !== '' && Number(props.currentUserId) === Number(props.comment.userId)
)
const canDelete = computed(
  () => isOwn.value || (props.repoOwnerId !== '' && Number(props.repoOwnerId) === Number(props.currentUserId))
)
const isInline = computed(
  () => props.comment.commentType === 'INLINE' || props.comment.commentType === 2
)
const isFlat = computed(() => props.displayMode === 'flat')
const initials = computed(() => (props.comment.userName || '?').slice(0, 1).toUpperCase())
const hasReplies = computed(() => Array.isArray(props.comment.replies) && props.comment.replies.length > 0)

function baseName(path) {
  if (!path) return '未知文件'
  const index = path.lastIndexOf('/')
  return index < 0 ? path : path.slice(index + 1)
}

function startEdit() {
  editBody.value = props.comment.body || ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function submitEdit() {
  const body = editBody.value.trim()
  if (!body) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  saving.value = true
  try {
    await updateComment(props.owner, props.repo, props.prId, props.comment.id, body)
    editing.value = false
    ElMessage.success('评论已更新')
    emit('changed')
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    saving.value = false
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条评论吗？', '删除评论', {
      type: 'warning'
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    await deleteComment(props.owner, props.repo, props.prId, props.comment.id)
    ElMessage.success('评论已删除')
    emit('changed')
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="comment" :class="{ nested: depth > 0 && !isFlat, flat: isFlat }">
    <div class="avatar" aria-hidden="true">{{ initials }}</div>

    <div class="main">
      <div class="head">
        <span class="name">{{ comment.userName || '未知用户' }}</span>
        <span v-if="isFlat && replyToName" class="reply-to">
          回复 <b>@{{ replyToName }}</b>
        </span>
        <el-tag
          v-if="isInline"
          size="small"
          type="warning"
          effect="plain"
          class="inline-tag"
          :title="comment.filePath || ''"
        >
          行级 · {{ baseName(comment.filePath) }}<template v-if="comment.lineNumber">:{{ comment.lineNumber }}</template>
        </el-tag>
        <span class="muted time">{{ formatDateTime(comment.createTime) }}</span>
      </div>

      <template v-if="!editing">
        <div class="body">{{ comment.body }}</div>
        <div class="actions">
          <el-button text size="small" @click="emit('reply', comment)">回复</el-button>
          <el-button v-if="isOwn" text size="small" @click="startEdit">编辑</el-button>
          <el-button v-if="canDelete" text size="small" type="danger" :loading="deleting" @click="remove">
            删除
          </el-button>
        </div>
      </template>

      <template v-else>
        <el-input
          v-model="editBody"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 8 }"
          resize="none"
          placeholder="修改评论内容"
        />
        <div class="actions">
          <el-button size="small" type="primary" :loading="saving" @click="submitEdit">保存</el-button>
          <el-button size="small" text @click="cancelEdit">取消</el-button>
        </div>
      </template>

      <div v-if="hasReplies && !isFlat" class="replies">
        <CommentItem
          v-for="reply in comment.replies"
          :key="reply.id"
          :comment="reply"
          :owner="owner"
          :repo="repo"
          :pr-id="prId"
          :current-user-id="currentUserId"
          :repo-owner-id="repoOwnerId"
          :depth="depth + 1"
          :display-mode="displayMode"
          @reply="emit('reply', $event)"
          @changed="emit('changed')"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.comment {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-gradient);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.comment:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-1);
}

.comment + .comment {
  margin-top: 10px;
}

.comment.nested {
  background: var(--panel-inset);
}

.avatar {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.92), rgba(34, 211, 238, 0.55));
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.25);
}

.main {
  flex: 1;
  min-width: 0;
}

.head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.name {
  font-weight: 600;
  color: var(--text-1);
}

.reply-to {
  font-size: 12px;
  color: var(--text-3);
}

.reply-to b {
  color: var(--accent-2);
  font-weight: 600;
}

/* 平铺模式下由外层列表控制间距，避免叠加 margin */
.comment.flat + .comment.flat {
  margin-top: 0;
}

.time {
  font-size: 12px;
  margin-left: auto;
}

.inline-tag {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.body {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-1);
}

.actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
}

.replies {
  margin-top: 12px;
  padding-left: 12px;
  border-left: 1px solid var(--line);
}
</style>
