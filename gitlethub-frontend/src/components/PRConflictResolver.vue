<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { modifyStage } from '../api/pr'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  prId: { type: [Number, String], required: true },
  files: { type: Array, default: () => [] }
})

const emit = defineEmits(['saved', 'merge'])

const CONFLICT_MARKER = /^(?:<<<<<<< |=======$|>>>>>>> )/m

/** path -> 当前编辑中的文本；二进制文件不进入草稿 */
const drafts = ref({})
const savedDrafts = ref({})
/** 标记删除的路径；文件仍留在暂存区，撤销删除不需要重新拉取内容 */
const deletedPaths = ref(new Set())
const savedDeletedPaths = ref(new Set())
const selectedPath = ref('')
const saving = ref(false)

watch(
  () => props.files,
  (files) => {
    const next = {}
    const nextDeleted = new Set()
    for (const file of files) {
      if (!file.isBinary) next[file.path] = file.content ?? ''
      if (file.isDeleted) nextDeleted.add(file.path)
    }
    drafts.value = next
    savedDrafts.value = { ...next }
    deletedPaths.value = nextDeleted
    savedDeletedPaths.value = new Set(nextDeleted)
    if (!files.some((file) => file.path === selectedPath.value)) {
      selectedPath.value = files[0]?.path ?? ''
    }
  },
  { immediate: true }
)

const current = computed(() => props.files.find((file) => file.path === selectedPath.value) || null)

function isDeleted(path) {
  return deletedPaths.value.has(path)
}

const dirtyFiles = computed(() =>
  props.files.filter((file) => {
    const contentDirty =
      !file.isBinary && (drafts.value[file.path] ?? '') !== (savedDrafts.value[file.path] ?? '')
    const deleteDirty = deletedPaths.value.has(file.path) !== savedDeletedPaths.value.has(file.path)
    return contentDirty || deleteDirty
  })
)

const dirty = computed(() => dirtyFiles.value.length > 0)

function markerCount(path) {
  const text = drafts.value[path]
  if (typeof text !== 'string') return 0
  return text.split('\n').filter((line) => CONFLICT_MARKER.test(line + '\n')).length
}

const unresolved = computed(() =>
  props.files
    .filter((file) => !file.isBinary && !isDeleted(file.path) && markerCount(file.path) > 0)
    .map((file) => ({ path: file.path, count: markerCount(file.path) }))
)

const canMerge = computed(() => props.files.length > 0 && unresolved.value.length === 0)

function toggleDelete() {
  if (!current.value) return
  const path = current.value.path
  if (deletedPaths.value.has(path)) {
    deletedPaths.value.delete(path)
  } else {
    deletedPaths.value.add(path)
  }
}

function resetCurrent() {
  if (!current.value) return
  const path = current.value.path
  if (!current.value.isBinary) {
    drafts.value[path] = savedDrafts.value[path] ?? ''
  }
  if (savedDeletedPaths.value.has(path)) {
    deletedPaths.value.add(path)
  } else {
    deletedPaths.value.delete(path)
  }
}

async function save(thenMerge) {
  if (saving.value) return
  if (unresolved.value.length > 0) {
    const list = unresolved.value.map((item) => `${item.path}（${item.count} 处）`).join('、')
    ElMessage.warning('还有文件包含冲突标记：' + list)
    return
  }
  const changed = dirtyFiles.value.map((file) => ({
    path: file.path,
    // 二进制文件不改内容；content 为 null 时后端只更新删除标记
    content: file.isBinary ? null : drafts.value[file.path] ?? '',
    isDeleted: deletedPaths.value.has(file.path)
  }))
  if (changed.length === 0 && !thenMerge) {
    ElMessage.info('没有需要保存的修改')
    return
  }

  saving.value = true
  try {
    if (changed.length > 0) {
      await modifyStage(props.owner, props.repo, props.prId, changed)
      for (const file of changed) {
        if (!file.isDeleted) savedDrafts.value[file.path] = file.content
      }
      savedDeletedPaths.value = new Set(deletedPaths.value)
      const deleteCount = changed.filter((file) => file.isDeleted).length
      ElMessage.success(
        deleteCount > 0
          ? `已保存 ${changed.length - deleteCount} 个文件，标记删除 ${deleteCount} 个`
          : `已保存 ${changed.length} 个文件`
      )
      emit('saved')
    }
    if (thenMerge) emit('merge')
  } catch {
    // 错误提示由 http 拦截器统一弹出
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="resolver">
    <aside class="file-list">
      <div
        v-for="file in files"
        :key="file.path"
        class="file-item"
        :class="{ active: file.path === selectedPath, deleted: isDeleted(file.path) }"
        @click="selectedPath = file.path"
      >
        <span class="file-path mono">{{ file.path }}</span>
        <el-tag v-if="isDeleted(file.path)" size="small" type="info">已删除</el-tag>
        <el-tag v-else-if="file.isBinary" size="small" type="info">二进制</el-tag>
        <el-tag
          v-else-if="markerCount(file.path) > 0"
          size="small"
          type="danger"
        >{{ markerCount(file.path) }} 处冲突</el-tag>
        <el-tag v-else size="small" type="success">已解决</el-tag>
      </div>
    </aside>

    <section class="editor">
      <template v-if="current">
        <div class="editor-head">
          <span class="mono file-path">{{ current.path }}</span>
          <el-tag v-if="isDeleted(current.path)" size="small" type="info">合并时删除该文件</el-tag>
          <el-tag v-else-if="current.isBinary" size="small" type="info">二进制文件不支持在线编辑</el-tag>
        </div>

        <el-alert
          v-if="!isDeleted(current.path) && !current.isBinary && markerCount(current.path) > 0"
          :title="`该文件还有 ${markerCount(current.path)} 处冲突标记（<<<<<<< / ======= / >>>>>>>）`"
          type="warning"
          :closable="false"
          show-icon
        />

        <textarea
          v-if="!current.isBinary"
          v-model="drafts[current.path]"
          class="editor-body mono"
          spellcheck="false"
          :disabled="saving || isDeleted(current.path)"
        />
        <div v-else class="binary-note muted">
          该文件是二进制内容，无法在网页中编辑。请用 CLI 解决后重新合并。
        </div>

        <el-alert
          v-if="isDeleted(current.path)"
          title="该文件已标记为删除，合并后会从仓库移除；点「撤销删除」可以恢复保留。"
          type="info"
          :closable="false"
          show-icon
        />

        <footer class="editor-foot">
          <el-button :disabled="saving" @click="resetCurrent">撤销当前修改</el-button>
          <el-button
            :type="isDeleted(current.path) ? 'success' : 'danger'"
            plain
            :disabled="saving"
            @click="toggleDelete"
          >
            {{ isDeleted(current.path) ? '撤销删除' : '标记删除' }}
          </el-button>
          <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save(false)">保存</el-button>
          <el-button type="success" :loading="saving" :disabled="!canMerge" @click="save(true)">
            保存并重新合并
          </el-button>
        </footer>
      </template>
      <div v-else class="empty muted">暂存区没有冲突文件</div>
    </section>
  </div>
</template>

<style scoped>
.resolver {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 12px;
  min-height: 420px;
}

.file-list {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 6px;
  overflow: auto;
  max-height: 520px;
  background: var(--surface-2);
}

.file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.file-item:hover,
.file-item.active {
  background: var(--glass-hover);
}

.file-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-1);
}

.file-item.deleted .file-path {
  color: var(--text-3);
  text-decoration: line-through;
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.editor-body {
  width: 100%;
  min-height: 340px;
  resize: vertical;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-1);
  color: var(--text-0);
  font-size: 12.5px;
  line-height: 1.6;
  tab-size: 4;
  outline: none;
}

.editor-body:focus {
  border-color: var(--accent-1);
}

.editor-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.binary-note {
  padding: 40px 16px;
  text-align: center;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.empty {
  display: grid;
  place-items: center;
  min-height: 200px;
}

@media (max-width: 900px) {
  .resolver {
    grid-template-columns: minmax(0, 1fr);
  }

  .file-list {
    max-height: 180px;
  }
}
</style>
