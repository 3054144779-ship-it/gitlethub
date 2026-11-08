<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createTag, deleteTag, listTags } from '../api/tags'
import { formatDateTime, shortSha } from '../utils/format'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true }
})

const router = useRouter()
const tags = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const deletingName = ref('')
const form = ref({ tagName: '', commitSha: '', description: '' })

async function load() {
  loading.value = true
  try {
    tags.value = (await listTags(props.owner, props.repo)) || []
  } catch {
    tags.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { tagName: '', commitSha: '', description: '' }
  dialogVisible.value = true
}

function openCommit(row) {
  if (!row.commitSha) return
  router.push({
    name: 'commit',
    params: { owner: props.owner, repo: props.repo, sha: row.commitSha }
  })
}

/** 与后端保持一致：Git ref 命名规则的关键约束 */
function isValidTagName(name) {
  if (!name || name.startsWith('-') || name.startsWith('.')) return false
  if (
    name.endsWith('/') ||
    name.endsWith('.') ||
    name.includes('..') ||
    name.includes('//') ||
    name.includes('@{') ||
    name.includes('.lock') ||
    name === '@'
  ) {
    return false
  }
  for (const ch of name) {
    const code = ch.charCodeAt(0)
    if (code <= 0x20 || code === 0x7f || '~^:?*[\\'.includes(ch)) return false
  }
  return true
}

async function submit() {
  const tagName = form.value.tagName.trim()
  const commitSha = form.value.commitSha.trim()
  if (!tagName || !commitSha) {
    ElMessage.warning('标签名和提交不能为空')
    return
  }
  if (!isValidTagName(tagName)) {
    ElMessage.warning('标签名不符合 Git 引用命名规则')
    return
  }
  submitting.value = true
  try {
    await createTag(props.owner, props.repo, {
      tagName,
      commitSha,
      description: form.value.description.trim() || null
    })
    ElMessage.success('标签已创建')
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

async function remove(tag) {
  try {
    await ElMessageBox.confirm(
      `删除标签 ${tag.tagName} 后不可恢复，确定继续吗？`,
      '删除标签',
      { type: 'warning' }
    )
  } catch {
    return
  }
  deletingName.value = tag.tagName
  try {
    await deleteTag(props.owner, props.repo, tag.tagName)
    ElMessage.success('标签已删除')
    await load()
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    deletingName.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="tag-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">
        <el-icon><CollectionTag /></el-icon>
        <span class="btn-text">创建标签</span>
      </el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <span class="muted hint">标签指向一个固定提交，适合标记版本发布</span>
    </div>

    <el-table v-loading="loading" :data="tags" border>
      <el-table-column label="标签" min-width="180">
        <template #default="{ row }">
          <span class="tag-name">
            <el-icon class="tag-icon"><PriceTag /></el-icon>
            {{ row.tagName }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="指向提交" min-width="200">
        <template #default="{ row }">
          <span class="sha" @click="openCommit(row)">{{ shortSha(row.commitSha, 12) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220">
        <template #default="{ row }">
          <span v-if="row.description">{{ row.description }}</span>
          <span v-else class="muted">（无说明）</span>
        </template>
      </el-table-column>
      <el-table-column label="创建人" width="140">
        <template #default="{ row }">
          <span>{{ row.userName || '未知用户' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          <span class="muted">{{ formatDateTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button
            text
            size="small"
            type="danger"
            :loading="deletingName === row.tagName"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty v-if="!loading" description="还没有标签，创建第一个版本标记吧" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" title="创建标签" width="480px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="标签名">
          <el-input v-model="form.tagName" placeholder="例如 v1.0.0" maxlength="100" />
        </el-form-item>
        <el-form-item label="提交 SHA">
          <el-input v-model="form.commitSha" class="mono" placeholder="要标记的提交 SHA" />
        </el-form-item>
        <el-form-item label="说明（可选）">
          <el-input
            v-model="form.description"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            resize="none"
            maxlength="500"
            show-word-limit
            placeholder="这个版本的说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-text {
  margin-left: 4px;
}

.hint {
  font-size: 12px;
}

.tag-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.tag-icon {
  color: var(--accent-warn);
}

.sha {
  cursor: pointer;
  color: var(--accent-2);
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

.sha:hover {
  text-decoration: underline;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}
</style>
