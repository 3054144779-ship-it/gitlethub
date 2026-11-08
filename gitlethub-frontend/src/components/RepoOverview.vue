<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listBranches, listCommits } from '../api/repo'
import { listPullRequests } from '../api/pr'
import { listCollaborators } from '../api/collab'
import { formatDateTime, shortSha } from '../utils/format'
import { levelLabel, prStatusLabel, prStatusTagType } from '../utils/dict'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  repoInfo: { type: Object, default: null }
})

const emit = defineEmits(['navigate'])

const router = useRouter()
const loading = ref(false)
const commits = ref([])
const pulls = ref([])
const collaborators = ref([])
const branches = ref([])

const visibleBranches = computed(() => branches.value.slice(0, 5))
const visibleCollaborators = computed(() => collaborators.value.slice(0, 8))
const hiddenCollaboratorCount = computed(() => Math.max(0, collaborators.value.length - 8))

/** 各模块独立降级：某一项失败只让对应卡片为空，不拖垮整个概览页 */
async function load() {
  loading.value = true
  const [commitRes, pullRes, collabRes, branchRes] = await Promise.allSettled([
    listCommits(props.owner, props.repo, { size: 5 }),
    listPullRequests(props.owner, props.repo, { size: 5 }),
    listCollaborators(props.owner, props.repo),
    listBranches(props.owner, props.repo)
  ])
  commits.value = commitRes.status === 'fulfilled' ? commitRes.value || [] : []
  pulls.value = pullRes.status === 'fulfilled' ? pullRes.value || [] : []
  collaborators.value = collabRes.status === 'fulfilled' ? collabRes.value || [] : []
  branches.value = branchRes.status === 'fulfilled' ? branchRes.value || [] : []
  loading.value = false
}

function messageTitle(message) {
  return String(message || '').split('\n')[0] || '(无提交信息)'
}

function openCommit(row) {
  router.push({ name: 'commit', params: { owner: props.owner, repo: props.repo, sha: row.sha } })
}

function openPull(row) {
  router.push({ name: 'pull', params: { owner: props.owner, repo: props.repo, id: row.id } })
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="overview">
    <div class="ov-main">
      <section class="ov-card">
        <header class="ov-head">
          <h3>最近提交</h3>
          <el-button text size="small" @click="emit('navigate', 'commits')">
            全部 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </header>
        <ul v-if="commits.length" class="ov-list">
          <li v-for="row in commits" :key="row.sha" class="ov-row" @click="openCommit(row)">
            <span class="sha mono">{{ shortSha(row.sha) }}</span>
            <span class="row-main">{{ messageTitle(row.message) }}</span>
            <span class="row-meta">{{ row.authorName || '-' }} · {{ formatDateTime(row.time) }}</span>
          </li>
        </ul>
        <p v-else class="ov-empty">{{ loading ? '加载中…' : '还没有提交' }}</p>
      </section>

      <section class="ov-card">
        <header class="ov-head">
          <h3>最近 Pull Request</h3>
          <el-button text size="small" @click="emit('navigate', 'pulls')">
            全部 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </header>
        <ul v-if="pulls.length" class="ov-list">
          <li v-for="row in pulls" :key="row.id" class="ov-row" @click="openPull(row)">
            <el-tag :type="prStatusTagType(row.status)" size="small">
              {{ prStatusLabel(row.status) }}
            </el-tag>
            <span class="row-main">{{ row.title }}</span>
            <span class="row-meta">{{ row.authorName || '-' }} · {{ formatDateTime(row.createTime) }}</span>
          </li>
        </ul>
        <p v-else class="ov-empty">{{ loading ? '加载中…' : '还没有 Pull Request' }}</p>
      </section>
    </div>

    <aside class="ov-side">
      <section class="ov-card side-card">
        <h3 class="ov-title">仓库信息</h3>
        <dl class="facts">
          <div>
            <dt>描述</dt>
            <dd class="desc-value">{{ repoInfo?.description || '暂无描述' }}</dd>
          </div>
          <div>
            <dt>可见性</dt>
            <dd>{{ repoInfo?.visibility === 1 ? '公开' : '私有' }}</dd>
          </div>
          <div>
            <dt>默认分支</dt>
            <dd class="mono">{{ repoInfo?.defaultBranch || '-' }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{{ formatDateTime(repoInfo?.updateTime) }}</dd>
          </div>
        </dl>

        <div class="side-block">
          <header class="ov-head">
            <h3>协作者</h3>
            <span class="count">{{ collaborators.length }}</span>
          </header>
          <div v-if="visibleCollaborators.length" class="chips">
            <el-tag
              v-for="person in visibleCollaborators"
              :key="person.userId"
              size="small"
              effect="plain"
            >
              {{ person.username }}
              <span class="level">{{ levelLabel(person.level) }}</span>
            </el-tag>
            <span v-if="hiddenCollaboratorCount" class="more muted">+{{ hiddenCollaboratorCount }}</span>
          </div>
          <p v-else class="ov-empty">{{ loading ? '加载中…' : '暂无协作者' }}</p>
        </div>

        <div class="side-block">
          <header class="ov-head">
            <h3>分支</h3>
            <el-button text size="small" @click="emit('navigate', 'branches')">
              全部 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
            </el-button>
          </header>
          <ul v-if="visibleBranches.length" class="branch-list">
            <li v-for="row in visibleBranches" :key="row.branchName" class="branch-row">
              <el-icon class="branch-icon"><Share /></el-icon>
              <span class="mono branch-name">{{ row.branchName }}</span>
              <el-tag v-if="row.isDefault" size="small" type="primary" effect="plain">默认</el-tag>
            </li>
          </ul>
          <p v-else class="ov-empty">{{ loading ? '加载中…' : '暂无分支' }}</p>
        </div>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
  min-height: 200px;
}

.ov-main,
.ov-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.ov-card {
  padding: 20px;
  border-radius: var(--r-md);
  background: var(--panel-gradient);
  box-shadow: var(--card-shadow);
  backdrop-filter: blur(20px) saturate(150%);
  -webkit-backdrop-filter: blur(20px) saturate(150%);
}

.ov-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.ov-head h3,
.ov-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
  color: var(--text-2);
  text-transform: uppercase;
}

.ov-title {
  margin-bottom: 14px;
}

.ov-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ov-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  margin: 0 -16px;
  border-radius: 8px;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.ov-row:hover {
  background: var(--hover-1);
}

.row-main {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-0);
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.5;
}

.row-meta {
  color: var(--text-3);
  font-size: 12px;
  white-space: nowrap;
}

.sha {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--bg-0);
  color: var(--text-2);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
}

.facts {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  font-size: 12.5px;
}

.facts div {
  display: flex;
  justify-content: space-between;
  gap: 14px;
}

.facts dt {
  flex: 0 0 auto;
  color: var(--text-3);
}

.facts dd {
  margin: 0;
  color: var(--text-1);
  text-align: right;
  line-height: 1.5;
  word-break: break-word;
}

.desc-value {
  max-width: 176px;
}

.side-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.level {
  margin-left: 4px;
  color: var(--text-3);
  font-size: 11px;
}

.count {
  color: var(--text-3);
  font-size: 12px;
}

.more {
  align-self: center;
  font-size: 12px;
}

.branch-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.branch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  font-size: 12.5px;
}

.branch-icon {
  color: var(--text-3);
}

.branch-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-1);
}

.branch-row .el-tag {
  margin-left: auto;
}

.ov-empty {
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

@media (max-width: 960px) {
  .overview {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
