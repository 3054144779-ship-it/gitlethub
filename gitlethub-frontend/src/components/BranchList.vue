<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listBranches } from '../api/repo'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true }
})

const router = useRouter()
const branches = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    branches.value = (await listBranches(props.owner, props.repo)) || []
  } catch {
    branches.value = []
  } finally {
    loading.value = false
  }
}

function openCommit(row) {
  if (!row.commitSha) return
  router.push({
    name: 'commit',
    params: { owner: props.owner, repo: props.repo, sha: row.commitSha }
  })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <span class="muted">分支在推送到新分支名时会自动创建</span>
    </div>

    <el-table v-loading="loading" :data="branches" border>
      <el-table-column prop="branchName" label="分支" min-width="180">
        <template #default="{ row }">
          <span>{{ row.branchName }}</span>
          <el-tag v-if="row.isDefault === 1" size="small" class="tag">默认</el-tag>
          <el-tag v-if="row.isProtected === 1" size="small" type="warning" class="tag">受保护</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="指向提交" min-width="320">
        <template #default="{ row }">
          <span v-if="row.commitSha" class="sha" @click="openCommit(row)">{{ row.commitSha.slice(0, 12) }}</span>
          <span v-else class="muted">（空仓库，尚无提交）</span>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="90">
        <template #default="{ row }">
          <span class="muted mono">{{ row.version }}</span>
        </template>
      </el-table-column>
      <el-table-column label="允许强推" width="100">
        <template #default="{ row }">
          <el-tag :type="row.isForcePush === 1 ? 'danger' : 'info'" size="small">
            {{ row.isForcePush === 1 ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">
          <span class="muted">{{ row.updateTime ? String(row.updateTime).replace('T', ' ') : '-' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.tag {
  margin-left: 6px;
}
</style>
