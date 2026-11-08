<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getRepo } from '../api/repo'
import RepoHeader from '../components/RepoHeader.vue'
import RepoOverview from '../components/RepoOverview.vue'
import FileBrowser from '../components/FileBrowser.vue'
import CommitList from '../components/CommitList.vue'
import CommitGraph from '../components/CommitGraph.vue'
import BranchList from '../components/BranchList.vue'
import PRList from '../components/PRList.vue'
import RepoSettings from '../components/RepoSettings.vue'
import AIAssistant from '../components/AIAssistant.vue'

const route = useRoute()
const router = useRouter()

const owner = ref(route.params.owner)
const repo = ref(route.params.repo)
const repoInfo = ref(null)
const notFound = ref(false)
/** 标签已并入设置页，旧链接里的 ?tab=tags 直接落到设置 */
const TAB_ALIAS = { tags: 'settings' }
const TAB_NAMES = new Set(['overview', 'files', 'commits', 'branches', 'pulls', 'settings'])
function resolveTab(value) {
  const tab = TAB_ALIAS[value] || value
  return TAB_NAMES.has(tab) ? tab : 'overview'
}
const activeTab = ref(resolveTab(route.query.tab))
/** 提交 tab 的两种展示方式：图 / 表格（同一份数据的两种视图，不并排） */
const commitView = ref('graph')

/** 从提交详情页跳过来时带的定位参数：?tab=commits&sha=... 或 ?tab=files&ref=... */
const highlightSha = computed(() => route.query.sha || '')
const viewRef = computed(() => route.query.ref || '')

async function loadRepoInfo() {
  notFound.value = false
  try {
    repoInfo.value = await getRepo(owner.value, repo.value)
  } catch {
    repoInfo.value = null
    notFound.value = true
  }
}

function onSettingsUpdated() {
  loadRepoInfo()
}

/** 文件视图退出历史版本模式：把 URL 上的 ref 摘掉，viewRef 会自动变回空串 */
function clearViewRef() {
  const query = { ...route.query }
  delete query.ref
  router.replace({ query })
}

watch(
  () => [route.params.owner, route.params.repo],
  ([nextOwner, nextRepo]) => {
    owner.value = nextOwner
    repo.value = nextRepo
    loadRepoInfo()
  }
)

watch(activeTab, (tab) => {
  router.replace({ query: { ...route.query, tab } })
})

// 带 sha 跳过来是要在图里定位，这时必须切到图，否则定位参数没人消费
watch(
  () => route.query.sha,
  (sha) => {
    if (sha) commitView.value = 'graph'
  }
)

onMounted(loadRepoInfo)
</script>

<template>
  <div class="page">
    <el-result
      v-if="notFound"
      icon="warning"
      title="仓库不存在或无权访问"
      :sub-title="`${owner}/${repo} 不存在，或它是私有仓库、你没有访问权限`"
    >
      <template #extra>
        <el-button type="primary" @click="router.push({ name: 'repos' })">返回仓库列表</el-button>
      </template>
    </el-result>

    <template v-else>
      <RepoHeader :owner="owner" :repo="repo" :repo-info="repoInfo" />

      <div class="repo-toolbar">
        <AIAssistant :owner="owner" :repo="repo" label="AI 助手" />
        <span class="muted hint">解释代码、语义检索、代码考古或审查某个 PR</span>
      </div>

      <!-- key 绑到仓库上：切换仓库时整块重建，否则各 tab 会留着上一个仓库的数据 -->
      <el-tabs v-model="activeTab" :key="`${owner}/${repo}`">
        <el-tab-pane label="概览" name="overview">
          <RepoOverview
            v-if="activeTab === 'overview'"
            :owner="owner"
            :repo="repo"
            :repo-info="repoInfo"
            @navigate="activeTab = $event"
          />
        </el-tab-pane>
        <el-tab-pane label="文件" name="files">
          <FileBrowser
            v-if="activeTab === 'files'"
            :owner="owner"
            :repo="repo"
            :view-ref="viewRef"
            @update:view-ref="clearViewRef"
          />
        </el-tab-pane>
        <el-tab-pane label="提交" name="commits">
          <div v-if="activeTab === 'commits'" class="commit-pane">
            <el-radio-group v-model="commitView" size="small" class="commit-view-switch">
              <el-radio-button value="graph">提交图</el-radio-button>
              <el-radio-button value="table">提交列表</el-radio-button>
            </el-radio-group>
            <CommitGraph v-if="commitView === 'graph'" :owner="owner" :repo="repo" :highlight="highlightSha" />
            <CommitList v-else :owner="owner" :repo="repo" />
          </div>
        </el-tab-pane>
        <el-tab-pane label="分支" name="branches">
          <BranchList v-if="activeTab === 'branches'" :owner="owner" :repo="repo" />
        </el-tab-pane>
        <el-tab-pane label="Pull Request" name="pulls">
          <PRList v-if="activeTab === 'pulls'" :owner="owner" :repo="repo" />
        </el-tab-pane>
        <el-tab-pane label="设置" name="settings">
          <RepoSettings
            v-if="activeTab === 'settings'"
            :owner="owner"
            :repo="repo"
            :repo-info="repoInfo"
            @updated="onSettingsUpdated"
          />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<style scoped>
/* 图 / 表格二选一，各自占满宽度：两份视图用的是同一批提交，并排会重复显示 */
.commit-view-switch {
  margin-bottom: 12px;
}

.repo-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.repo-toolbar .hint {
  font-size: 12px;
}
</style>
