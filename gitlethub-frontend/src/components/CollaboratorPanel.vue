<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addCollaborator,
  listCollaborators,
  removeCollaborator,
  updateAutoCreatePr
} from '../api/collab'
import { useAuthStore } from '../stores/auth'
import { GRANTABLE_LEVELS, levelLabel, levelTagType } from '../utils/dict'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true }
})

const auth = useAuthStore()
const collaborators = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ username: '', level: 1 })
/** 正在切换自动 PR 的那一行 userId：用来给开关上 loading，防重复点 */
const switchingUserId = ref(null)

/** 只有「我自己」那一行的自动 PR 可改，别人的保持只读 */
function isSelf(row) {
  return auth.userId !== '' && String(row.userId) === String(auth.userId)
}

async function load() {
  loading.value = true
  try {
    collaborators.value = (await listCollaborators(props.owner, props.repo)) || []
  } catch {
    collaborators.value = []
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.username) {
    ElMessage.warning('请输入用户名')
    return
  }
  submitting.value = true
  try {
    await addCollaborator(props.owner, props.repo, { username: form.username, level: form.level })
    ElMessage.success('已添加协作者')
    dialogVisible.value = false
    form.username = ''
    form.level = 1
    await load()
  } catch {
    // 错误提示已由 http 拦截器统一弹出（例如用户不存在、无权限）
  } finally {
    submitting.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`确定移除协作者 ${row.username || row.userId} 吗？`, '移除协作者', {
    type: 'warning'
  })
  try {
    await removeCollaborator(props.owner, props.repo, row.userId)
    ElMessage.success('已移除')
    await load()
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  }
}

/** 切换自己的自动 PR。用受控开关（:model-value + @change），请求成功才落值，失败自动弹回原状 */
async function toggleAutoPr(row, next) {
  switchingUserId.value = row.userId
  try {
    await updateAutoCreatePr(props.owner, props.repo, next)
    row.autoCreatePr = next ? 1 : 0
    ElMessage.success(next ? '已开启自动 PR（仅对本仓库生效）' : '已关闭自动 PR（仅对本仓库生效）')
  } catch {
    // 失败：不改 row，开关弹回；错误提示已由 http 拦截器统一弹出
  } finally {
    switchingUserId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="dialogVisible = true">添加协作者</el-button>
      <span class="muted">只有仓库所有者可以增删协作者</span>
    </div>

    <el-table v-loading="loading" :data="collaborators" border>
      <el-table-column label="用户" min-width="200">
        <template #default="{ row }">
          {{ row.username || `用户#${row.userId}` }}
        </template>
      </el-table-column>
      <el-table-column label="用户 ID" width="120">
        <template #default="{ row }">
          <span class="mono muted">{{ row.userId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="等级" width="120">
        <template #default="{ row }">
          <el-tag :type="levelTagType(row.level)" size="small">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="自动 PR" width="110">
        <template #default="{ row }">
          <el-switch
            v-if="isSelf(row)"
            :model-value="row.autoCreatePr === 1"
            :loading="switchingUserId === row.userId"
            @change="(val) => toggleAutoPr(row, val)"
          />
          <el-tag v-else :type="row.autoCreatePr === 1 ? 'success' : 'info'" size="small">
            {{ row.autoCreatePr === 1 ? '开启' : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="加入时间" width="180">
        <template #default="{ row }">
          <span class="muted">{{ row.createTime ? String(row.createTime).replace('T', ' ') : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :disabled="String(row.level) === 'OWNER' || row.level === 2"
            @click="remove(row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="muted">暂无协作者</span>
      </template>
    </el-table>

    <p class="muted auto-pr-hint">
      「自动 PR」是你自己的设置：只有你自己那一行可改，且只对当前仓库生效。
    </p>

    <el-dialog v-model="dialogVisible" title="添加协作者" width="420px">
      <el-form label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="已注册的用户名" />
        </el-form-item>
        <el-form-item label="等级">
          <el-radio-group v-model="form.level">
            <el-radio v-for="item in GRANTABLE_LEVELS" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.auto-pr-hint {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
