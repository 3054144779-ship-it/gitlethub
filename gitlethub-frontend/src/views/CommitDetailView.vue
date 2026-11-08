<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCommit, getCommitDiff, getRepo } from '../api/repo'
import RepoHeader from '../components/RepoHeader.vue'
import AIAssistant from '../components/AIAssistant.vue'
import { changeTypeTagType } from '../utils/dict'

const route = useRoute()
const router = useRouter()

const owner = route.params.owner
const repo = route.params.repo
const sha = route.params.sha

const repoInfo = ref(null)
const commit = ref(null)
const diff = ref([])
const loading = ref(false)
const failed = ref(false)

// 非 PR 场景服务端不会组装 diff，把本页的提交信息和前几个文件内容作为上下文拼进 query
const aiContext = computed(() => {
  if (!commit.value) return ''
  const files = (diff.value || []).slice(0, 3).map((change) => {
    const oldText = (change.oldContent ?? '').slice(0, 4000)
    const newText = (change.newContent ?? '').slice(0, 4000)
    return `文件: ${change.path}\n旧内容:\n${oldText}\n新内容:\n${newText}`
  })
  return [
    `提交 ${sha}`,
    `提交信息: ${commit.value.message || '（无）'}`,
    ...files
  ].join('\n\n')
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [commitDetail, changes] = await Promise.all([
      getCommit(owner, repo, sha),
      getCommitDiff(owner, repo, sha)
    ])
    commit.value = commitDetail
    diff.value = changes || []
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    repoInfo.value = await getRepo(owner, repo)
  } catch {
    repoInfo.value = null
  }
  await load()
})
</script>

<template>
  <div class="page">
    <RepoHeader :owner="owner" :repo="repo" :repo-info="repoInfo" />

    <div class="toolbar">
      <el-button @click="router.push({ name: 'repo', params: { owner, repo }, query: { tab: 'commits' } })">
        返回提交历史
      </el-button>
      <el-button @click="router.push({ name: 'repo', params: { owner, repo }, query: { tab: 'commits', sha } })">
        在提交图中查看
      </el-button>
      <el-button @click="router.push({ name: 'repo', params: { owner, repo }, query: { tab: 'files', ref: sha } })">
        查看此提交的文件
      </el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <AIAssistant
        :owner="owner"
        :repo="repo"
        :context-text="aiContext"
        label="AI 解读提交"
      />
    </div>

    <el-alert
      v-if="failed"
      type="error"
      :closable="false"
      title="读取提交失败"
      description="该提交可能不在服务端对象库中（例如对象存储目录与推送时不一致）。"
    />

    <template v-else>
      <el-card v-loading="loading" class="detail-card">
        <h3 class="commit-message">{{ commit?.message }}</h3>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="提交 SHA">
            <span class="mono">{{ sha }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="作者">
            {{ commit?.authorName || '未知（该历史提交没有提交记录）' }}
          </el-descriptions-item>
          <el-descriptions-item label="时间">
            <span class="mono">{{ commit?.time }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="第一父提交">
            <span v-if="commit?.parent" class="sha" @click="router.push({ name: 'commit', params: { owner, repo, sha: commit.parent } })">
              {{ commit.parent.slice(0, 12) }}
            </span>
            <span v-else class="muted">无（根提交）</span>
          </el-descriptions-item>
          <el-descriptions-item label="第二父提交">
            <span v-if="commit?.secondParent" class="sha" @click="router.push({ name: 'commit', params: { owner, repo, sha: commit.secondParent } })">
              {{ commit.secondParent.slice(0, 12) }}
            </span>
            <span v-else class="muted">无（非合并提交）</span>
          </el-descriptions-item>
          <el-descriptions-item label="被追踪文件">
            {{ commit?.trackedFiles ? Object.keys(commit.trackedFiles).length : 0 }} 个
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <h3 class="section-title">与第一父提交的差异（{{ diff.length }} 个文件）</h3>

      <el-empty v-if="!loading && !diff.length" description="没有文件变更" />

      <el-card v-for="change in diff" :key="change.path" class="file-card" shadow="never">
        <div class="file-head">
          <el-tag :type="changeTypeTagType(change.changeType)" size="small">{{ change.changeType }}</el-tag>
          <span class="mono file-path">{{ change.path }}</span>
          <span class="muted mono">-{{ change.oldSha ? change.oldSha.slice(0, 7) : '新文件' }}
            +{{ change.newSha ? change.newSha.slice(0, 7) : '已删除' }}</span>
        </div>
        <div class="diff-body">
          <div class="diff-col">
            <div class="diff-label muted">旧内容</div>
            <pre class="code-block">{{ change.oldContent ?? '（无 / 二进制）' }}</pre>
          </div>
          <div class="diff-col">
            <div class="diff-label muted">新内容</div>
            <pre class="code-block">{{ change.newContent ?? '（无 / 二进制）' }}</pre>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.detail-card {
  margin-bottom: 20px;
}

.commit-message {
  margin: 0 0 16px;
  font-size: 18px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 16px;
}

.file-card {
  margin-bottom: 12px;
  border: 1px solid var(--line);
}

.file-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.file-path {
  font-weight: 600;
  flex: 1;
}

.diff-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.diff-label {
  font-size: 12px;
  margin-bottom: 4px;
}

@media (max-width: 900px) {
  .diff-body {
    grid-template-columns: 1fr;
  }
}
</style>
