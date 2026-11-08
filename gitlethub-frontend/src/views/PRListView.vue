<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRepo } from '../api/repo'
import RepoHeader from '../components/RepoHeader.vue'
import PRList from '../components/PRList.vue'

const route = useRoute()
const router = useRouter()
const owner = route.params.owner
const repo = route.params.repo
const repoInfo = ref(null)

onMounted(async () => {
  try {
    repoInfo.value = await getRepo(owner, repo)
  } catch {
    repoInfo.value = null
  }
})
</script>

<template>
  <div class="page">
    <RepoHeader :owner="owner" :repo="repo" :repo-info="repoInfo" />

    <div class="toolbar">
      <el-button @click="router.push({ name: 'repo', params: { owner, repo }, query: { tab: 'pulls' } })">
        返回仓库
      </el-button>
    </div>

    <PRList :owner="owner" :repo="repo" />
  </div>
</template>
