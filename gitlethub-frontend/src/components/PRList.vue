<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listPullRequests } from '../api/pr'
import { prStatusLabel, prStatusTagType } from '../utils/dict'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true }
})

const router = useRouter()
const status = ref('') // '' 表示全部
const pulls = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)

async function load() {
  loading.value = true
  try {
    pulls.value =
      (await listPullRequests(props.owner, props.repo, {
        status: status.value === '' ? undefined : status.value,
        page: page.value,
        size: size.value
      })) || []
  } catch {
    pulls.value = []
  } finally {
    loading.value = false
  }
}

function openPull(row) {
  router.push({
    name: 'pull',
    params: { owner: props.owner, repo: props.repo, id: row.id }
  })
}

watch(status, () => {
  page.value = 1
  load()
})

watch(page, load)

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="muted">状态：</span>
      <el-select v-model="status" style="width: 160px">
        <el-option label="全部" value="" />
        <el-option label="开放" :value="0" />
        <el-option label="已关闭" :value="1" />
        <el-option label="已合并" :value="2" />
      </el-select>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button
        type="primary"
        @click="router.push({ name: 'pull-new', params: { owner: props.owner, repo: props.repo } })"
      >
        新建 Pull Request
      </el-button>
    </div>

    <el-table v-loading="loading" :data="pulls" border @row-click="openPull">
      <el-table-column label="#" width="80">
        <template #default="{ row }">
          <span class="sha">#{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="分支" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.sourceBranch }} → {{ row.targetBranch }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="prStatusTagType(row.status)" size="small">{{ prStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="作者" width="130">
        <template #default="{ row }">
          <span v-if="row.authorName">{{ row.authorName }}</span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          <span class="muted">{{ row.createTime ? String(row.createTime).replace('T', ' ') : '-' }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <span class="muted">暂无 Pull Request</span>
      </template>
    </el-table>

    <el-pagination
      class="pager"
      layout="prev, pager, next, total"
      :current-page="page"
      :page-size="size"
      :total="pulls.length < size ? (page - 1) * size + pulls.length : page * size + 1"
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
