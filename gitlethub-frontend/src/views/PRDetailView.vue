<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPullRequest, getStage, mergePullRequest, closePullRequest } from '../api/pr'
import { getRepo } from '../api/repo'
import RepoHeader from '../components/RepoHeader.vue'
import AIAssistant from '../components/AIAssistant.vue'
import PRConflictResolver from '../components/PRConflictResolver.vue'
import PRDiscussion from '../components/PRDiscussion.vue'
import PRDiffView from '../components/PRDiffView.vue'
import PRReviewPanel from '../components/PRReviewPanel.vue'
import PRAiReview from '../components/PRAiReview.vue'
import { useAuthStore } from '../stores/auth'
import { normalizePrStatus, prStatusLabel, prStatusTagType } from '../utils/dict'
import { formatDateTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const owner = route.params.owner
const repo = route.params.repo
const id = route.params.id

const repoInfo = ref(null)
const pull = ref(null)
const loading = ref(false)
const merging = ref(false)
const closing = ref(false)
const stageFiles = ref([])

const socialTab = ref('discussion')

const statusName = computed(() => normalizePrStatus(pull.value?.status))
const isOpen = computed(() => statusName.value === 'OPEN')
const isMerged = computed(() => statusName.value === 'MERGED')
const isClosed = computed(() => statusName.value === 'CLOSED')

// 仅仓库所有者可合并（服务端仍会最终裁定，这里只是不给出无效入口）
const auth = useAuthStore()
const isOwner = computed(
  () => repoInfo.value?.ownerId != null && Number(auth.userId) === Number(repoInfo.value.ownerId)
)

async function load() {
  loading.value = true
  try {
    pull.value = await getPullRequest(owner, repo, id)
  } catch {
    pull.value = null
  } finally {
    loading.value = false
  }
  await refreshStage()
}

/** 只有 OPEN + OWNER 才需要拉暂存区；没有冲突时后端返回空数组 */
async function refreshStage() {
  if (!pull.value || !isOpen.value || !isOwner.value) {
    stageFiles.value = []
    return
  }
  try {
    const files = await getStage(owner, repo, pull.value.id)
    stageFiles.value = Array.isArray(files) ? files : []
  } catch {
    stageFiles.value = []
  }
}

async function merge(confirm = true) {
  if (confirm) {
    await ElMessageBox.confirm('合并会尝试把源分支合入目标分支，可能产生冲突。继续？', '合并 Pull Request', {
      type: 'warning'
    })
  }
  merging.value = true
  try {
    await mergePullRequest(owner, repo, id)
    ElMessage.success('合并成功')
    stageFiles.value = []
    await load()
  } catch {
    await refreshStage()
    // 其余失败原因已由 http 拦截器统一提示
    if (stageFiles.value.length > 0) {
      socialTab.value = 'conflicts'
      ElMessage.warning('合并存在冲突，请在「冲突解决」中处理后再试')
    }
  } finally {
    merging.value = false
  }
}

async function close() {
  await ElMessageBox.confirm('确定关闭这个 Pull Request 吗？', '关闭 Pull Request', { type: 'warning' })
  closing.value = true
  try {
    await closePullRequest(owner, repo, id)
    ElMessage.success('已关闭')
    await load()
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    closing.value = false
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
      <el-button @click="router.push({ name: 'pulls', params: { owner, repo } })">返回 PR 列表</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <AIAssistant :owner="owner" :repo="repo" :pr-id="pull?.id ?? null" label="AI 对话" />
    </div>

    <div v-loading="loading" class="pr-shell">
      <div v-if="!loading && !pull" class="empty-state">
        <el-icon><Warning /></el-icon>
        <p>Pull Request 不存在或已被删除</p>
      </div>

      <template v-else-if="pull">
        <div class="pr-head">
          <h2 class="pr-title">
            {{ pull.title }} <span class="muted">#{{ pull.id }}</span>
          </h2>
          <el-tag :type="prStatusTagType(pull.status)" size="large">
            {{ prStatusLabel(pull.status) }}
          </el-tag>
        </div>

        <div class="pr-layout">
          <div class="pr-main">
            <section class="desc-panel">
              <div class="muted label">描述</div>
              <div class="description">{{ pull.description || '（无描述）' }}</div>
            </section>

            <el-card class="social-card">
              <el-tabs v-model="socialTab">
                <el-tab-pane label="讨论" name="discussion">
                  <PRDiscussion
                    v-if="socialTab === 'discussion'"
                    :owner="owner"
                    :repo="repo"
                    :pr-id="pull.id"
                    :repo-owner-id="repoInfo?.ownerId ?? ''"
                    @open-diff="socialTab = 'diff'"
                  />
                </el-tab-pane>
                <el-tab-pane label="变更" name="diff">
                  <PRDiffView
                    v-if="socialTab === 'diff'"
                    :owner="owner"
                    :repo="repo"
                    :pr-id="pull.id"
                    :repo-owner-id="repoInfo?.ownerId ?? ''"
                  />
                </el-tab-pane>
                <el-tab-pane
                  v-if="isOpen && isOwner && stageFiles.length"
                  :label="`冲突解决 (${stageFiles.length})`"
                  name="conflicts"
                >
                  <PRConflictResolver
                    v-if="socialTab === 'conflicts'"
                    :owner="owner"
                    :repo="repo"
                    :pr-id="pull.id"
                    :files="stageFiles"
                    @saved="refreshStage"
                    @merge="merge(false)"
                  />
                </el-tab-pane>
                <el-tab-pane label="评审" name="reviews">
                  <PRReviewPanel
                    v-if="socialTab === 'reviews'"
                    :owner="owner"
                    :repo="repo"
                    :pr-id="pull.id"
                    :pull="pull"
                    :repo-owner-id="repoInfo?.ownerId ?? ''"
                  />
                </el-tab-pane>
                <el-tab-pane label="AI 审查" name="ai">
                  <PRAiReview v-if="socialTab === 'ai'" :owner="owner" :repo="repo" :pr-id="pull.id" />
                </el-tab-pane>
              </el-tabs>
            </el-card>
          </div>

          <aside class="pr-side">
            <section class="side-card">
              <h3 class="side-title">概要</h3>

              <div class="side-branches">
                <span class="branch-chip mono">{{ pull.sourceBranch }}</span>
                <el-icon class="flow-arrow"><Right /></el-icon>
                <span class="branch-chip mono">{{ pull.targetBranch }}</span>
              </div>

              <dl class="side-facts">
                <div>
                  <dt>作者</dt>
                  <dd>{{ pull.authorName || '-' }}</dd>
                </div>
                <div>
                  <dt>创建</dt>
                  <dd>{{ formatDateTime(pull.createTime) }}</dd>
                </div>
                <div>
                  <dt>更新</dt>
                  <dd>{{ formatDateTime(pull.updateTime) }}</dd>
                </div>
              </dl>

              <div class="side-actions">
                <el-button
                  v-if="isOpen && isOwner && stageFiles.length"
                  type="warning"
                  class="block"
                  @click="socialTab = 'conflicts'"
                >
                  解决冲突（{{ stageFiles.length }}）
                </el-button>
                <el-button
                  v-if="isOpen && isOwner"
                  type="primary"
                  class="block"
                  :loading="merging"
                  @click="merge()"
                >
                  {{ stageFiles.length ? '重新合并' : '合并' }}
                </el-button>
                <el-button
                  v-if="isOpen"
                  type="danger"
                  plain
                  class="block"
                  :loading="closing"
                  @click="close"
                >
                  关闭
                </el-button>
                <p v-if="isOpen && !isOwner" class="muted note">只有仓库所有者可以合并该 PR</p>
                <p v-if="isMerged" class="muted note">该 PR 已合并</p>
                <p v-if="isClosed" class="muted note">该 PR 已关闭</p>
              </div>

              <p v-if="stageFiles.length" class="side-tip muted">
                该 PR 有 {{ stageFiles.length }} 个文件等待解决冲突。去掉文件里的
                <span class="mono">&lt;&lt;&lt;&lt;&lt;&lt;&lt; / ======= / &gt;&gt;&gt;&gt;&gt;&gt;&gt;</span>
                标记后保存并重新合并；不需要保留的文件可以直接「标记删除」。
              </p>
              <p v-else class="side-tip muted">
                合并出现冲突时，冲突文件会进入服务端暂存区，仓库所有者可直接在「冲突解决」中在线处理。
              </p>
            </section>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.pr-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.pr-title {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 20px;
  color: var(--text-0);
  word-break: break-word;
}

.pr-head .el-tag {
  flex: 0 0 auto;
}

.pr-shell {
  min-height: 160px;
}

.pr-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.pr-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.desc-panel {
  padding: 20px;
  border-radius: var(--r-md);
  background: var(--panel-gradient);
  box-shadow: var(--card-shadow);
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.label {
  font-size: 12px;
  margin-bottom: 8px;
  color: var(--text-3);
}

.description {
  white-space: pre-wrap;
  min-height: 40px;
  color: var(--text-1);
  line-height: 1.65;
}

.pr-side {
  position: sticky;
  top: 84px;
  min-width: 0;
}

.side-card {
  padding: 20px;
  border-radius: var(--r-md);
  background: var(--panel-gradient);
  box-shadow: var(--card-shadow);
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.side-title {
  margin: 0 0 14px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: var(--text-2);
  text-transform: uppercase;
}

.side-branches {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.branch-chip {
  padding: 3px 10px;
  border-radius: 8px;
  background: var(--bg-0);
  color: var(--text-1);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.flow-arrow {
  color: var(--text-3);
  font-size: 13px;
}

.side-facts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 12.5px;
}

.side-facts div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.side-facts dt {
  flex: 0 0 auto;
  color: var(--text-3);
}

.side-facts dd {
  margin: 0;
  color: var(--text-1);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.side-actions .block {
  width: 100%;
  height: 36px;
  margin-left: 0;
}

.note {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
}

.side-tip {
  margin: 16px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  line-height: 1.6;
}

.social-card {
  margin-top: 0;
}

@media (max-width: 960px) {
  .pr-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .pr-side {
    position: static;
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 72px 0;
  color: var(--text-3);
  font-size: 13px;
}

.empty-state .el-icon {
  font-size: 28px;
}

.empty-state p {
  margin: 0;
}
</style>
