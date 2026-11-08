<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPullRequestDiff } from '../api/pr'
import { createComment, listComments } from '../api/social'
import { useAuthStore } from '../stores/auth'
import { commentDisplayMode } from '../utils/commentMode'
import { flattenComments } from '../utils/comments'
import { changeTypeTagType } from '../utils/dict'
import CommentItem from './CommentItem.vue'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true },
  repoOwnerId: { type: [Number, String], default: '' }
})

const auth = useAuthStore()
const currentUserId = computed(() => auth.userId)

const files = ref([])
const loading = ref(false)
const comments = ref([])
const commentsLoading = ref(false)
const collapsed = ref(new Set())
const collapsedThreads = ref(new Set())

const composerAnchor = ref(null)
const replyTarget = ref(null)
const composerBody = ref('')
const composerInput = ref(null)
const submitting = ref(false)

const refreshing = computed(() => loading.value || commentsLoading.value)

/** key = filePath|diffSide|lineNumber，与后端行级评论的定位字段一一对应 */
function anchorOf(file, line) {
  const diffSide = line.type === 'del' ? 0 : 1
  const lineNumber = diffSide === 0 ? line.oldLine : line.newLine
  if (lineNumber == null) return null
  return {
    filePath: file.path,
    diffSide,
    lineNumber,
    key: `${file.path}|${diffSide}|${lineNumber}`
  }
}

const commentsByAnchor = computed(() => {
  const map = new Map()
  for (const comment of comments.value) {
    if (!comment.filePath || comment.lineNumber == null) continue
    const side = comment.diffSide == null ? 1 : Number(comment.diffSide)
    const key = `${comment.filePath}|${side}|${comment.lineNumber}`
    const list = map.get(key)
    if (list) list.push(comment)
    else map.set(key, [comment])
  }
  return map
})

function threadsOf(file, line) {
  const anchor = anchorOf(file, line)
  return anchor ? commentsByAnchor.value.get(anchor.key) || [] : []
}

function isComposerOn(file, line) {
  const anchor = anchorOf(file, line)
  return Boolean(anchor && composerAnchor.value?.key === anchor.key)
}

function threadKey(file, line) {
  return anchorOf(file, line)?.key || ''
}

function isThreadCollapsed(file, line) {
  return collapsedThreads.value.has(threadKey(file, line))
}

/** 顶层 + 所有嵌套回复的总数 */
function threadCount(file, line) {
  const count = (nodes) => (nodes || []).reduce((sum, node) => sum + 1 + count(node.replies), 0)
  return count(threadsOf(file, line))
}

function displayThreads(file, line) {
  const thread = threadsOf(file, line)
  return commentDisplayMode.value === 'flat'
    ? flattenComments(thread)
    : thread.map((comment) => ({ comment, replyToName: '' }))
}

function threadPreview(file, line) {
  const flat = flattenComments(threadsOf(file, line))
  const latest = flat[flat.length - 1]?.comment
  return latest ? `${latest.userName || '匿名'}：${latest.body}` : ''
}

function toggleThread(key) {
  if (!key) return
  const next = new Set(collapsedThreads.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedThreads.value = next
}

function expandThread(key) {
  if (!key || !collapsedThreads.value.has(key)) return
  const next = new Set(collapsedThreads.value)
  next.delete(key)
  collapsedThreads.value = next
}

function signOf(line) {
  if (line.type === 'add') return '+'
  if (line.type === 'del') return '-'
  return ' '
}

function dirOf(path) {
  const index = path.lastIndexOf('/')
  return index < 0 ? '' : path.slice(0, index + 1)
}

function baseOf(path) {
  const index = path.lastIndexOf('/')
  return index < 0 ? path : path.slice(index + 1)
}

function changeTypeLabel(changeType) {
  if (changeType === 'ADDED') return '新增'
  if (changeType === 'DELETED') return '删除'
  return '修改'
}

function statsOf(file) {
  let add = 0
  let del = 0
  for (const line of file.lines || []) {
    if (line.type === 'add') add += 1
    else if (line.type === 'del') del += 1
  }
  return { add, del }
}

function toggleCollapse(path) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

async function loadDiff() {
  loading.value = true
  try {
    files.value = (await getPullRequestDiff(props.owner, props.repo, props.prId)) || []
  } catch {
    files.value = []
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  commentsLoading.value = true
  try {
    const data = await listComments(props.owner, props.repo, props.prId, {
      commentType: 2,
      pageNum: 1,
      pageSize: 200
    })
    comments.value = data?.records || []
  } catch {
    comments.value = []
  } finally {
    commentsLoading.value = false
  }
}

function refresh() {
  return Promise.all([loadDiff(), loadComments()])
}

function focusComposer() {
  nextTick(() => composerInput.value?.focus?.())
}

function setComposerInput(el) {
  composerInput.value = el
}

function openComposer(file, line) {
  const anchor = anchorOf(file, line)
  if (!anchor) return
  if (composerAnchor.value?.key !== anchor.key) {
    composerBody.value = ''
    replyTarget.value = null
  }
  composerAnchor.value = anchor
  focusComposer()
}

function openReplyComposer(file, line, comment) {
  const anchor = anchorOf(file, line)
  if (!anchor) return
  expandThread(anchor.key)
  composerAnchor.value = anchor
  replyTarget.value = comment
  composerBody.value = ''
  focusComposer()
}

function closeComposer() {
  composerAnchor.value = null
  replyTarget.value = null
  composerBody.value = ''
}

async function submit() {
  const text = composerBody.value.trim()
  if (!text) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  const anchor = composerAnchor.value
  if (!anchor) return
  const payload = replyTarget.value
    ? { body: text, commentType: 2, replyToId: replyTarget.value.id }
    : {
        body: text,
        commentType: 2,
        filePath: anchor.filePath,
        lineNumber: anchor.lineNumber,
        diffSide: anchor.diffSide
      }
  submitting.value = true
  try {
    await createComment(props.owner, props.repo, props.prId, payload)
    ElMessage.success(replyTarget.value ? '回复已发布' : '行级评论已发布')
    closeComposer()
    await loadComments()
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.prId,
  () => {
    closeComposer()
    collapsed.value = new Set()
    refresh()
  }
)

onMounted(refresh)
</script>

<template>
  <div class="diff-view">
    <div class="panel-toolbar">
      <span class="muted summary">
        {{ files.length }} 个文件 · {{ comments.length }} 条行级评论
      </span>
      <el-radio-group v-model="commentDisplayMode" size="small" class="mode-switch">
        <el-radio-button value="tree">树状</el-radio-button>
        <el-radio-button value="flat">平铺</el-radio-button>
      </el-radio-group>
      <el-button text size="small" class="refresh" :loading="refreshing" @click="refresh">刷新</el-button>
    </div>

    <el-empty v-if="!loading && !files.length" description="两个分支的内容没有差异" />

    <div v-loading="loading" class="files">
      <div v-for="file in files" :key="file.path" class="diff-file">
        <button type="button" class="file-head" @click="toggleCollapse(file.path)">
          <span class="caret" :class="{ off: collapsed.has(file.path) }">▾</span>
          <span class="mono path" :title="file.path">
            <span v-if="dirOf(file.path)" class="path-dir">{{ dirOf(file.path) }}</span>
            <span class="path-name">{{ baseOf(file.path) }}</span>
          </span>
          <el-tag size="small" :type="changeTypeTagType(file.changeType)" effect="plain">
            {{ changeTypeLabel(file.changeType) }}
          </el-tag>
          <span class="stats">
            <span class="add">+{{ statsOf(file).add }}</span>
            <span class="del">-{{ statsOf(file).del }}</span>
          </span>
          <el-tag v-if="file.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
          <span v-if="file.binary" class="muted binary">二进制</span>
        </button>

        <div v-if="!collapsed.has(file.path)" class="file-body">
          <div v-if="file.binary" class="note">二进制文件，不展示内容</div>
          <div v-else-if="!file.lines || !file.lines.length" class="note">无内容变化</div>
          <template v-else>
            <div v-for="(line, index) in file.lines" :key="index" class="line-group">
              <div class="diff-row" :class="line.type">
                <span class="gutter">
                  <button
                    v-if="anchorOf(file, line)"
                    type="button"
                    class="add-comment"
                    title="在这一行发表行级评论"
                    @click="openComposer(file, line)"
                  >
                    +
                  </button>
                </span>
                <span class="ln old">{{ line.oldLine ?? '' }}</span>
                <span class="ln new">{{ line.newLine ?? '' }}</span>
                <span class="sign">{{ signOf(line) }}</span>
                <span class="code">{{ line.content === '' ? ' ' : line.content }}</span>
              </div>

              <div v-if="threadsOf(file, line).length" class="thread">
                <button
                  type="button"
                  class="thread-head"
                  @click="toggleThread(threadKey(file, line))"
                >
                  <span class="thread-caret" :class="{ off: isThreadCollapsed(file, line) }">▾</span>
                  <span class="thread-count">{{ threadCount(file, line) }} 条行级评论</span>
                  <span v-if="isThreadCollapsed(file, line)" class="thread-preview">
                    {{ threadPreview(file, line) }}
                  </span>
                </button>

                <el-collapse-transition>
                  <div v-show="!isThreadCollapsed(file, line)" class="thread-body">
                    <CommentItem
                      v-for="entry in displayThreads(file, line)"
                      :key="entry.comment.id"
                      :comment="entry.comment"
                      :display-mode="commentDisplayMode"
                      :reply-to-name="entry.replyToName"
                      :owner="owner"
                      :repo="repo"
                      :pr-id="prId"
                      :current-user-id="currentUserId"
                      :repo-owner-id="repoOwnerId"
                      @reply="openReplyComposer(file, line, $event)"
                      @changed="loadComments"
                    />
                  </div>
                </el-collapse-transition>
              </div>

              <div v-if="isComposerOn(file, line)" class="inline-composer">
                <div class="composer-title">
                  <span v-if="replyTarget">
                    回复 <b>@{{ replyTarget.userName || '未知用户' }}</b>
                  </span>
                  <span v-else class="mono">
                    行级评论 · {{ anchorOf(file, line).diffSide === 0 ? '旧行' : '新行' }}
                    {{ anchorOf(file, line).lineNumber }}
                  </span>
                  <el-button text size="small" @click="closeComposer">取消</el-button>
                </div>
                <el-input
                  :ref="setComposerInput"
                  v-model="composerBody"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  resize="none"
                  :placeholder="replyTarget ? '写下你的回复' : '针对这一行写点什么，例如：这里缺少空值判断'"
                />
                <div class="composer-actions">
                  <span class="hint">行级评论对仓库协作者可见</span>
                  <el-button type="primary" size="small" :loading="submitting" @click="submit">发布</el-button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diff-view {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.summary {
  font-size: 12px;
}

.mode-switch {
  margin-left: auto;
}

.files {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.diff-file {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--panel-gradient);
  box-shadow: var(--shadow-1);
}

.file-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: rgba(148, 163, 184, 0.05);
  color: var(--text-1);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.file-head:hover {
  background: rgba(99, 102, 241, 0.1);
}

.caret {
  color: var(--text-3);
  transition: transform var(--dur) var(--ease);
}

.caret.off {
  transform: rotate(-90deg);
}

.path {
  display: flex;
  align-items: baseline;
  min-width: 0;
  font-size: 13px;
}

.path-dir {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-3);
}

.path-name {
  flex: 0 0 auto;
  font-weight: 600;
  white-space: nowrap;
  color: var(--text-1);
}

.stats {
  margin-left: auto;
  display: flex;
  gap: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.stats .add {
  color: var(--accent-ok);
}

.stats .del {
  color: var(--accent-danger);
}

.binary {
  font-size: 12px;
}

.file-body {
  overflow-x: auto;
}

.note {
  padding: 14px;
  color: var(--text-3);
  font-size: 13px;
}

.diff-row {
  display: flex;
  align-items: stretch;
  width: max-content;
  min-width: 100%;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 20px;
  border-left: 3px solid transparent;
  transition: background var(--dur) var(--ease);
}

.diff-row.add {
  background: rgba(52, 211, 153, 0.08);
  border-left-color: rgba(52, 211, 153, 0.5);
}

.diff-row.del {
  background: rgba(248, 113, 113, 0.08);
  border-left-color: rgba(248, 113, 113, 0.5);
}

.diff-row:hover {
  background: rgba(99, 102, 241, 0.12);
}

.gutter {
  flex: 0 0 26px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.add-comment {
  width: 18px;
  height: 18px;
  padding: 0;
  border: 1px solid rgba(99, 102, 241, 0.5);
  border-radius: 6px;
  background: rgba(99, 102, 241, 0.22);
  color: var(--code-inline-text);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--dur) var(--ease), background var(--dur) var(--ease);
}

.diff-row:hover .add-comment,
.add-comment:focus-visible {
  opacity: 1;
}

.add-comment:hover {
  background: rgba(99, 102, 241, 0.45);
}

.ln {
  flex: 0 0 46px;
  padding-right: 8px;
  text-align: right;
  color: var(--text-3);
  user-select: none;
}

.ln.new {
  border-right: 1px solid var(--line);
}

.sign {
  flex: 0 0 16px;
  text-align: center;
  color: var(--text-3);
  user-select: none;
}

.diff-row.add .sign {
  color: var(--accent-ok);
}

.diff-row.del .sign {
  color: var(--accent-danger);
}

.code {
  flex: 1;
  padding-right: 14px;
  color: var(--text-1);
  white-space: pre;
  tab-size: 4;
}

.thread {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: var(--panel-inset);
}

.thread-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 12px 5px 72px;
  border: 0;
  background: transparent;
  color: var(--text-2);
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.thread-head:hover {
  background: rgba(99, 102, 241, 0.08);
  color: var(--text-1);
}

.thread-caret {
  color: var(--text-3);
  transition: transform var(--dur) var(--ease);
}

.thread-caret.off {
  transform: rotate(-90deg);
}

.thread-count {
  flex: 0 0 auto;
}

.thread-preview {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-3);
}

.thread-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 900px;
  padding: 2px 12px 8px 72px;
}

/* 行级线程里的评论卡片压紧，避免把 diff 撑得太高 */
.thread :deep(.comment) {
  padding: 8px 10px;
  gap: 8px;
  border-radius: 10px;
}

.thread :deep(.comment + .comment) {
  margin-top: 0;
}

.thread :deep(.avatar) {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  font-size: 11px;
}

.thread :deep(.head) {
  margin-bottom: 4px;
  gap: 6px;
}

.thread :deep(.name) {
  font-size: 12.5px;
}

.thread :deep(.time) {
  font-size: 11px;
}

.thread :deep(.body) {
  font-size: 12.5px;
  line-height: 1.55;
}

.thread :deep(.actions) {
  margin-top: 2px;
}

.thread :deep(.actions .el-button) {
  height: 20px;
  padding: 0 4px;
  font-size: 11px;
}

.thread :deep(.replies) {
  margin-top: 6px;
  padding-left: 8px;
}

/* 线程已挂在具体代码行下，不再重复展示文件:行号标签 */
.thread :deep(.inline-tag) {
  display: none;
}

.inline-composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px 10px 72px;
  border-top: 1px solid rgba(99, 102, 241, 0.25);
  background: rgba(99, 102, 241, 0.07);
}

.composer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: var(--text-2);
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

@media (hover: none) {
  .add-comment {
    opacity: 0.45;
  }
}
</style>
