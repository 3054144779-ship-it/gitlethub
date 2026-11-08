<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateRepo } from '../api/repo'
import CollaboratorPanel from './CollaboratorPanel.vue'
import TagPanel from './TagPanel.vue'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  repoInfo: { type: Object, default: null }
})

const emit = defineEmits(['updated'])

const saving = ref(false)
const form = reactive({ description: '', visibility: 0, allowAutoPr: 1 })

watch(
  () => props.repoInfo,
  (info) => {
    if (!info) return
    form.description = info.description || ''
    form.visibility = info.visibility ?? 0
    form.allowAutoPr = info.allowAutoPr ?? 1
  },
  { immediate: true }
)

async function save() {
  saving.value = true
  try {
    // 后端 updateRemoteRepository 用 owner + repo 定位记录，并把 repo 名一并写回
    await updateRepo({
      owner: props.owner,
      repo: props.repo,
      description: form.description,
      visibility: form.visibility,
      allowAutoPr: form.allowAutoPr
    })
    ElMessage.success('配置已保存')
    emit('updated')
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-form :model="form" label-width="120px" class="settings-form">
    <el-form-item label="仓库路径">
      <el-input :model-value="`${owner}/${repo}`" disabled />
    </el-form-item>
    <el-form-item label="描述">
      <el-input v-model="form.description" type="textarea" :rows="3" placeholder="仓库描述" />
    </el-form-item>
    <el-form-item label="可见性">
      <el-radio-group v-model="form.visibility">
        <el-radio :value="0">私有</el-radio>
        <el-radio :value="1">公开</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item label="自动创建 PR">
      <el-switch v-model="form.allowAutoPr" :active-value="1" :inactive-value="0" />
      <span class="muted hint">推送到非默认分支时是否自动创建 PR</span>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </el-form-item>
  </el-form>

  <el-divider />

  <h3 class="section-title">协作者</h3>
  <CollaboratorPanel :owner="owner" :repo="repo" />

  <el-divider />

  <h3 class="section-title">标签</h3>
  <TagPanel :owner="owner" :repo="repo" />
</template>

<style scoped>
.settings-form {
  max-width: 560px;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 16px;
}
</style>
