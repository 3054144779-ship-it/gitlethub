<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRepo, listBranches, listCommits } from '../api/repo'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true }
})

const router = useRouter()

const branches = ref([])
const branch = ref('')
const commits = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)

async function init() {
  try {
    const [branchList, repoInfo] = await Promise.all([
      listBranches(props.owner, props.repo),
      getRepo(props.owner, props.repo)
    ])
    branches.value = branchList || []
    branch.value = repoInfo?.defaultBranch || branches.value[0]?.branchName || ''
  } catch {
    branches.value = []
  }
  await load()
}

async function load() {
  if (!branch.value) {
    commits.value = []
    return
  }
  loading.value = true
  try {
    commits.value = (await listCommits(props.owner, props.repo, {
      branch: branch.value,
      page: page.value,
      size: size.value
    })) || []
  } catch {
    commits.value = []
  } finally {
    loading.value = false
  }
}

function openCommit(row) {
  router.push({
    name: 'commit',
    params: { owner: props.owner, repo: props.repo, sha: row.sha }
  })
}

watch(branch, () => {
  page.value = 1
  load()
})

watch(page, load)

onMounted(init)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="muted">分支：</span>
      <el-select v-model="branch" placeholder="选择分支" style="width: 220px" :disabled="!branches.length">
        <el-option v-for="b in branches" :key="b.branchName" :label="b.branchName" :value="b.branchName" />
      </el-select>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="commits" border @row-click="openCommit">
      <el-table-column label="提交" width="140">
        <template #default="{ row }">
          <span class="sha">{{ row.sha.slice(0, 10) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="260" show-overflow-tooltip />
      <el-table-column label="作者" width="140">
        <template #default="{ row }">
          <span v-if="row.authorName">{{ row.authorName }}</span>
          <span v-else class="muted">未知</span>
        </template>
      </el-table-column>
      <el-table-column label="分支" width="140">
        <template #default="{ row }">
          <span v-if="row.branchName">{{ row.branchName }}</span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="合并提交" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.secondParent" type="warning" size="small">merge</el-tag>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="time" label="时间（CLI 格式）" min-width="200">
        <template #default="{ row }">
          <span class="muted">{{ row.time }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <span class="muted">没有提交（请先用 CLI 推送）</span>
      </template>
    </el-table>

    <!-- 后端返回的是普通列表、没有 total，这里按「本页是否满页」推断是否还有下一页 -->
    <el-pagination
      v-if="branch"
      class="pager"
      layout="prev, pager, next, total"
      :current-page="page"
      :page-size="size"
      :total="commits.length < size ? (page - 1) * size + commits.length : page * size + 1"
      @current-change="(value) => (page = value)"
    />
  </div>
</template>

<style scoped>
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
