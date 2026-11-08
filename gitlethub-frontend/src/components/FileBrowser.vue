<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight, Document, Folder, FolderOpened } from '@element-plus/icons-vue'
import { listBranches, getTree, getBlob } from '../api/repo'
import { fileTypeLabel, highlightCode, languageLabel } from '../utils/highlight'
import { ElMessage } from 'element-plus'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  /** 用某个提交 sha 看当时的历史版本；空串 = 看分支最新（来自 URL 的 ?ref=） */
  viewRef: { type: String, default: '' }
})

const emit = defineEmits(['update:viewRef'])

const branches = ref([])
const refName = ref('')
const entries = ref([])
const loading = ref(false)
const expanded = ref(new Set())

/** 历史版本模式：ref 是一个提交 sha，不跟随任何分支 */
const snapshotMode = computed(() => !!props.viewRef)
/** 实际查询用的 ref：历史版本优先，否则用选中的分支 */
const activeRef = computed(() => props.viewRef || refName.value)
const defaultBranchName = computed(
  () => (branches.value.find((b) => b.isDefault === 1) || branches.value[0])?.branchName || ''
)
/** 建库初始化的分支存在但没有任何提交：不请求文件树，避免后端返回“分支不存在” */
const isEmptyBranch = computed(
  () =>
    !snapshotMode.value &&
    !!refName.value &&
    !branches.value.find((b) => b.branchName === refName.value)?.commitSha
)

const dialogVisible = ref(false)
const blobLoading = ref(false)
const blob = ref(null)

const highlighted = computed(() => {
  if (!blob.value || blob.value.isBinary) return { html: '', language: '' }
  return highlightCode(blob.value.content, blob.value.path)
})
const highlightedLanguage = computed(() => languageLabel(highlighted.value.language))
const lineCount = computed(() => {
  const content = blob.value?.content
  if (!content) return 1
  return content.split('\n').length
})

function formatSize(text) {
  if (!text) return '0 B'
  const bytes = new TextEncoder().encode(text).length
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

const fileSize = computed(() => formatSize(blob.value?.content))

/** gitlet 的 trackedFiles 是扁平 path→blobSha，这里在前端拼成目录树 */
const tree = computed(() => buildTree(entries.value))

const visibleRows = computed(() => {
  const rows = []
  const walk = (nodes, depth) => {
    for (const node of nodes) {
      rows.push({ ...node, depth, childCount: node.children?.length || 0 })
      if (node.type === 'dir' && expanded.value.has(node.path)) {
        walk(node.children, depth + 1)
      }
    }
  }
  walk(tree.value, 0)
  return rows
})

function buildTree(list) {
  const root = { type: 'dir', children: [] }
  const ensureChild = (parent, name, type, path) => {
    let child = parent.children.find((item) => item.name === name && item.type === type)
    if (!child) {
      child = { name, type, path, children: type === 'dir' ? [] : undefined }
      parent.children.push(child)
    }
    return child
  }

  for (const entry of list || []) {
    const parts = String(entry.path || '').split('/').filter(Boolean)
    if (!parts.length) continue
    let parent = root
    parts.forEach((part, index) => {
      const isFile = index === parts.length - 1
      const path = parts.slice(0, index + 1).join('/')
      const node = ensureChild(parent, part, isFile ? 'file' : 'dir', path)
      if (isFile) node.blobSha = entry.blobSha
      else parent = node
    })
  }

  sortTree(root.children)
  return root.children
}

function sortTree(nodes) {
  nodes.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
    return a.name.localeCompare(b.name)
  })
  for (const node of nodes) {
    if (node.children?.length) sortTree(node.children)
  }
}

function toggleDir(path) {
  const next = new Set(expanded.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  expanded.value = next
}

function dirOf(path) {
  const index = path.lastIndexOf('/')
  return index < 0 ? '' : path.slice(0, index)
}

async function loadBranches() {
  try {
    branches.value = (await listBranches(props.owner, props.repo)) || []
    const preferred =
      branches.value.find((b) => b.isDefault === 1) || branches.value[0]
    // 默认分支可能还没有提交（commitSha 为空），此时列表为空
    if (preferred) {
      refName.value = preferred.branchName
    }
  } catch {
    branches.value = []
  }
}

async function loadTree() {
  if (!activeRef.value || isEmptyBranch.value) {
    entries.value = []
    return
  }
  loading.value = true
  try {
    entries.value = (await getTree(props.owner, props.repo, activeRef.value)) || []
    // 只有一个顶层目录时直接展开，省一次点击
    const roots = tree.value
    expanded.value = new Set(
      roots.length === 1 && roots[0].type === 'dir' ? [roots[0].path] : []
    )
  } catch {
    entries.value = []
  } finally {
    loading.value = false
  }
}

async function openFile(row) {
  dialogVisible.value = true
  blobLoading.value = true
  blob.value = null
  try {
    blob.value = await getBlob(props.owner, props.repo, activeRef.value, row.path)
  } catch {
    dialogVisible.value = false
  } finally {
    blobLoading.value = false
  }
}

/** 退出历史版本模式：交给父组件清掉 URL 上的 ref，activeRef 会自动落回选中的分支 */
function backToBranch() {
  emit('update:viewRef', '')
}

function onBranchChange() {
  // 手动选了分支就说明不想再看历史版本了
  if (snapshotMode.value) backToBranch()
}

watch(activeRef, loadTree)

onMounted(async () => {
  await loadBranches()
  await loadTree()
  if (!branches.value.length) {
    ElMessage.info('该仓库还没有分支（请先用 CLI 推送）')
  }
})
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="muted">分支：</span>
      <el-select
        v-model="refName"
        placeholder="选择分支"
        style="width: 220px"
        :disabled="!branches.length"
        @change="onBranchChange"
      >
        <el-option
          v-for="branch in branches"
          :key="branch.branchName"
          :label="branch.branchName + (branch.isDefault === 1 ? '（默认）' : '')"
          :value="branch.branchName"
        />
      </el-select>
      <el-button :loading="loading" @click="loadTree">刷新</el-button>
      <span v-if="!snapshotMode && refName" class="muted sha-hint">
        指向：<span class="mono">{{ (branches.find((b) => b.branchName === refName)?.commitSha || '（空）').slice(0, 12) }}</span>
      </span>
    </div>

    <el-alert v-if="snapshotMode" type="warning" :closable="false" class="snapshot-tip">
      <template #title>
        正在查看历史版本：<span class="mono">{{ viewRef.slice(0, 12) }}</span>
      </template>
      <template #default>
        <span>这是该提交当时的文件快照，不是任何分支的最新状态。</span>
        <el-button v-if="defaultBranchName" link type="primary" size="small" @click="backToBranch">
          回到 {{ defaultBranchName }}
        </el-button>
      </template>
    </el-alert>

    <div v-loading="loading" class="file-tree">
      <div class="file-tree-head">
        <span class="head-name">名称</span>
        <span>类型</span>
        <span>目录</span>
        <span class="head-action">操作</span>
      </div>

      <button
        v-for="row in visibleRows"
        :key="row.path"
        type="button"
        class="file-row"
        :class="row.type"
        :title="row.type === 'file' ? `${row.path}\nblob ${row.blobSha}` : row.path"
        :aria-expanded="row.type === 'dir' ? expanded.has(row.path) : undefined"
        @click="row.type === 'dir' ? toggleDir(row.path) : openFile(row)"
      >
        <span class="name-cell" :style="{ paddingLeft: row.depth * 18 + 'px' }">
          <span v-if="row.type === 'dir'" class="caret">{{ expanded.has(row.path) ? '▾' : '▸' }}</span>
          <span v-else class="caret placeholder">·</span>

          <el-icon class="icon" :class="row.type">
            <FolderOpened v-if="row.type === 'dir' && expanded.has(row.path)" />
            <Folder v-else-if="row.type === 'dir'" />
            <Document v-else />
          </el-icon>

          <span class="name">{{ row.name }}</span>
        </span>

        <span class="file-type">
          {{ row.type === 'dir' ? `文件夹 · ${row.childCount}` : fileTypeLabel(row.path) }}
        </span>
        <span class="file-dir">{{ row.type === 'file' && dirOf(row.path) ? dirOf(row.path) : '—' }}</span>
        <span class="row-action">
          {{ row.type === 'dir' ? (expanded.has(row.path) ? '收起' : '展开') : '查看' }}
          <el-icon><ArrowRight /></el-icon>
        </span>
      </button>

      <div v-if="!loading && !visibleRows.length" class="empty muted">
        <template v-if="isEmptyBranch">
          {{ refName }} 分支还没有任何提交，推送第一个提交后这里会显示文件树
        </template>
        <template v-else>该提交没有文件，或分支还没有任何提交</template>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" class="file-dialog" width="80%" top="5vh">
      <template #header>
        <div class="code-head">
          <span class="code-file">
            <el-icon><Document /></el-icon>
            <span class="code-path mono">{{ blob?.path || '文件内容' }}</span>
          </span>
          <span v-if="blob && !blob.isBinary" class="code-tags">
            <span v-if="highlightedLanguage" class="code-pill">{{ highlightedLanguage }}</span>
            <span class="code-pill">{{ lineCount }} 行</span>
            <span class="code-pill">{{ fileSize }}</span>
          </span>
        </div>
      </template>

      <div v-loading="blobLoading" class="code-body">
        <template v-if="blob">
          <el-alert
            v-if="blob.isBinary"
            type="warning"
            :closable="false"
            title="二进制文件，不展示内容"
            class="binary-tip"
          />
          <div v-else class="code-view">
            <div class="code-scroll">
              <div class="code-gutter" aria-hidden="true">
                <span v-for="line in lineCount" :key="line">{{ line }}</span>
              </div>
              <pre class="code-block"><code class="hljs" v-html="highlighted.html"></code></pre>
            </div>
          </div>
          <div class="blob-meta muted mono">blob {{ blob.blobSha }}</div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.sha-hint {
  font-size: 13px;
}

.snapshot-tip {
  margin-bottom: 12px;
}

.binary-tip {
  margin-bottom: 8px;
}

/* ---------- 代码查看弹窗 ---------- */
.code-head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding-right: 14px;
}

.code-file {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--text-0);
  font-size: 13.5px;
  font-weight: 600;
}

.code-file .el-icon {
  flex: 0 0 auto;
  color: var(--accent-2);
  font-size: 15px;
}

.code-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-tags {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.code-pill {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--hover-2);
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
}

.code-body {
  min-height: 120px;
}

.code-view {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--code-bg);
  overflow: hidden;
}

.code-scroll {
  display: flex;
  max-height: 68vh;
  overflow: auto;
}

.code-gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: flex-end;
  min-width: 44px;
  padding: 14px 10px 14px 12px;
  border-right: 1px solid var(--line);
  background: var(--code-bg);
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.65;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.code-view .code-block {
  flex: 0 0 auto;
  min-width: max-content;
  max-height: none;
  margin: 0;
  padding: 14px 16px;
  border: 0;
  border-radius: 0;
  background: transparent;
  overflow: visible;
  font-size: 13px;
  line-height: 1.65;
}

.code-view .code-block code {
  font-family: inherit;
  white-space: pre;
}

.blob-meta {
  margin-top: 10px;
  font-size: 11px;
}

.file-tree {
  background: transparent;
}

/* 表头与行共用一套网格，列始终对齐 */
.file-tree-head,
.file-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px minmax(0, 0.9fr) 84px;
  align-items: center;
  gap: 14px;
}

.file-tree-head {
  margin: 0 -12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--table-head-bg);
  color: var(--text-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.head-action {
  justify-self: end;
}

.file-row {
  width: calc(100% + 24px);
  min-height: 46px;
  margin: 0 -12px;
  padding: 12px 12px;
  border: 0;
  border-bottom: 1px solid var(--line-soft);
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.file-row:last-child {
  border-bottom: 0;
}

.file-row:hover,
.file-row:focus-visible {
  background: var(--hover-1);
}

.file-row:focus-visible {
  outline: none;
}

.file-row.dir {
  color: var(--text-0);
  font-weight: 600;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.caret {
  flex: 0 0 12px;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1;
  text-align: center;
}

.caret.placeholder {
  color: transparent;
}

.icon {
  flex: 0 0 auto;
  font-size: 16px;
}

.icon.dir,
.icon.file {
  color: var(--text-2);
}

.name {
  min-width: 0;
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-row.dir .name {
  font-weight: 600;
}

.file-type {
  color: var(--text-2);
  font-size: 12px;
}

.file-dir {
  min-width: 0;
  overflow: hidden;
  color: var(--text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-action {
  justify-self: end;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--accent-2);
  font-size: 12px;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.file-row:hover .row-action,
.file-row:focus-visible .row-action {
  opacity: 1;
}

.empty {
  padding: 16px 0;
  font-size: 13px;
}

@media (max-width: 720px) {
  .file-tree-head {
    display: none;
  }

  .file-row {
    grid-template-columns: minmax(0, 1fr) 92px;
  }

  .file-dir,
  .row-action {
    display: none;
  }
}
</style>
