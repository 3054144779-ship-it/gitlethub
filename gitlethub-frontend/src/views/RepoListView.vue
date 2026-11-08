<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createRepo, listRepos } from '../api/repo'
import { listPullRequests } from '../api/pr'
import { searchUsers } from '../api/user'
import { useAuthStore } from '../stores/auth'
import { normalizePrStatus } from '../utils/dict'
import { formatDateTime } from '../utils/format'
import AIInlineChat from '../components/AIInlineChat.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const loading = ref(false)
const repos = ref([])
const query = reactive({ owner: auth.username || '', keyword: '' })
/** 顶栏全局搜索写入 ?q=，这里仍保留同一套解析逻辑；窄屏顶栏搜索隐藏时页面内也有输入框 */
const searchText = ref('')
const page = ref(1)
/** 每页 12 个：仓库多时翻页，不会无限往下堆 */
const size = ref(12)
/**
 * 后端不返回总数：多取 1 条用来判断还有没有下一页，
 * 避免「刚好满页」时点下一页得到空列表。
 */
const hasNext = ref(false)

/** 工作台：聚合当前页仓库的开放 PR */
const openPrs = ref([])
const workspaceLoading = ref(false)
const workspaceFailed = ref(0)
const workspaceRequested = ref(0)
const workspaceTruncated = ref(false)
let listSeq = 0
let prSeq = 0
let searchSeq = 0

const viewingOwn = computed(
  () =>
    !!auth.username &&
    repos.value.length > 0 &&
    repos.value.every((row) => row.ownerName === auth.username)
)

/** 概览只放摘要和最近动态，完整列表交给侧栏的「仓库」「待处理 PR」 */
const view = computed(() => {
  if (route.query.q) return 'repos'
  if (route.hash === '#repos') return 'repos'
  if (route.hash === '#pulls') return 'pulls'
  return 'overview'
})

const pageTitle = computed(() => {
  if (view.value === 'repos') return '仓库'
  if (view.value === 'pulls') return '待处理 PR'
  return '概览'
})

const overviewPrs = computed(() => openPrs.value.slice(0, 5))
const overviewRepos = computed(() => repos.value.slice(0, 5))
const visiblePrs = computed(() => (view.value === 'overview' ? overviewPrs.value : openPrs.value))
const visibleRepos = computed(() =>
  view.value === 'overview' ? overviewRepos.value : repos.value
)

const workspaceAllFailed = computed(
  () =>
    !workspaceLoading.value &&
    workspaceRequested.value > 0 &&
    openPrs.value.length === 0 &&
    workspaceFailed.value === workspaceRequested.value
)

/**
 * 空结果文案。后端已把无权查看的私有仓库过滤掉，所以「搜别人搜不到」是正常结果，
 * 不能一律说「没有仓库」误导成对方没有仓库。
 */
const emptyHint = computed(() => {
  if (query.keyword) return `没有匹配「${query.keyword}」的可访问仓库`
  if (query.owner && query.owner !== auth.username) {
    return `${query.owner} 没有可访问的仓库（私有仓库仅对所有者与协作者可见）`
  }
  return '还没有仓库，点右上角「新建仓库」开始'
})

const dialogVisible = ref(false)
const creating = ref(false)
const createFormRef = ref()
const createForm = reactive({
  repo: '',
  description: '',
  visibility: 0,
  allowAutoPr: 1
})

const createRules = {
  repo: [
    { required: true, message: '请输入仓库名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9._-]+$/,
      message: '只能包含字母、数字、点、下划线、连字符',
      trigger: 'blur'
    }
  ]
}

/** 新一次查询开始后，上一轮的待办聚合结果立刻作废，避免旧数据残留 */
function invalidateWorkspace() {
  prSeq += 1
  openPrs.value = []
  workspaceLoading.value = false
  workspaceFailed.value = 0
  workspaceRequested.value = 0
  workspaceTruncated.value = false
}

/**
 * 没有全局 PR 聚合接口，只能对当前页每个仓库拉一次开放 PR。
 * 单个仓库失败不影响其他仓库；失败数与请求数相同时由界面给出重试入口。
 */
async function aggregateOpenPrs(rows) {
  const seq = ++prSeq
  openPrs.value = []
  workspaceFailed.value = 0
  workspaceTruncated.value = false
  workspaceRequested.value = rows.length

  if (!rows.length) {
    workspaceLoading.value = false
    return
  }

  workspaceLoading.value = true
  const results = await Promise.allSettled(
    rows.map((row) =>
      listPullRequests(row.ownerName, row.name, { status: 0, size: 20, silent: true }).then(
        (list) => ({ row, list: Array.isArray(list) ? list : [] })
      )
    )
  )
  if (seq !== prSeq) return

  const merged = []
  let failed = 0
  for (const result of results) {
    if (result.status !== 'fulfilled') {
      failed += 1
      continue
    }
    const { row, list } = result.value
    if (list.length >= 20) workspaceTruncated.value = true
    for (const pr of list) {
      if (normalizePrStatus(pr.status) !== 'OPEN') continue
      merged.push({
        ...pr,
        repoOwner: row.ownerName,
        repoName: row.name,
        repoFullName: `${row.ownerName}/${row.name}`
      })
    }
  }

  const seen = new Set()
  openPrs.value = merged
    .filter((pr) => {
      const key = `${pr.repoFullName}#${pr.id}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((a, b) => String(b.createTime || '').localeCompare(String(a.createTime || '')))

  workspaceFailed.value = failed
  workspaceLoading.value = false
}

async function load() {
  if (!query.owner) {
    ElMessage.warning('请先填写仓库所有者')
    return
  }

  const seq = ++listSeq
  // await 期间 query 可能又被改掉，先把这次请求的参数定下来
  const params = {
    owner: query.owner,
    keyword: query.keyword,
    page: page.value,
    size: size.value + 1
  }
  invalidateWorkspace()
  loading.value = true

  try {
    const fetched = (await listRepos(params)) || []
    if (seq !== listSeq) return
    hasNext.value = fetched.length > size.value
    const rows = fetched.slice(0, size.value)
    repos.value = rows
    if (rows.length && rows.every((row) => row.ownerName === auth.username)) {
      aggregateOpenPrs(rows)
    }
  } catch {
    if (seq !== listSeq) return
    repos.value = []
    hasNext.value = false
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

async function search() {
  const raw = searchText.value.trim()
  page.value = 1

  // 空串：回到自己的仓库
  if (!raw) {
    query.owner = auth.username || ''
    query.keyword = ''
    load()
    return
  }

  // 用户名/仓库名：斜杠语法始终优先，用于消解用户名与仓库重名的歧义
  const slash = raw.indexOf('/')
  if (slash >= 0) {
    query.owner = raw.slice(0, slash).trim() || auth.username || ''
    query.keyword = raw.slice(slash + 1).trim()
    load()
    return
  }

  // 输入自己的用户名：直接列自己的仓库，不用多一次用户查询
  if (raw.toLowerCase() === String(auth.username).toLowerCase()) {
    query.owner = auth.username
    query.keyword = ''
    load()
    return
  }

  // 其他裸输入：先看是否恰好是某个用户名，否则当作仓库名关键字
  const seq = ++searchSeq
  loading.value = true
  let matchedOwner = ''
  try {
    const result = await searchUsers(
      { username: raw, pageNum: 1, pageSize: 10 },
      { silent: true }
    )
    if (seq !== searchSeq) return
    const records = Array.isArray(result)
      ? result
      : Array.isArray(result?.records)
        ? result.records
        : []
    const exact =
      records.find((user) => user.username === raw) ||
      records.find((user) => String(user.username).toLowerCase() === raw.toLowerCase())
    matchedOwner = exact?.username || ''
  } catch {
    if (seq !== searchSeq) return
  }

  if (matchedOwner) {
    query.owner = matchedOwner
    query.keyword = ''
  } else {
    query.owner = auth.username || ''
    query.keyword = raw
  }
  load()
}

function changePage(next) {
  page.value = next
  load()
}

function goView(hash) {
  router.push({ name: 'repos', hash, query: {} })
}

async function submitCreate() {
  await createFormRef.value.validate()
  creating.value = true
  try {
    // 所有者恒为登录用户：后端也只认 token 里的身份，不认这里传的 owner
    await createRepo({ owner: auth.username, repo: createForm.repo, ...createForm })
    ElMessage.success('仓库创建成功')
    dialogVisible.value = false
    Object.assign(createForm, { repo: '', description: '', visibility: 0, allowAutoPr: 1 })
    // 清掉搜索条件，确保刚创建的仓库出现在列表里
    searchText.value = ''
    await search()
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    creating.value = false
  }
}

function openRepo(row) {
  router.push({ name: 'repo', params: { owner: row.ownerName, repo: row.name } })
}

function openPr(pr) {
  router.push({ name: 'pull', params: { owner: pr.repoOwner, repo: pr.repoName, id: pr.id } })
}

onMounted(async () => {
  const q = route.query.q
  if (typeof q === 'string' && q.trim()) {
    searchText.value = q
    await search()
  } else {
    await load()
  }
})

watch(
  () => route.query.q,
  (q) => {
    if (typeof q === 'string' && q.trim()) {
      searchText.value = q
      search()
      return
    }
    if (q !== undefined) return
    // 清空全局搜索或点侧栏「概览」：回到自己名下的仓库
    searchText.value = ''
    query.owner = auth.username || ''
    query.keyword = ''
    page.value = 1
    load()
  }
)
</script>

<template>
  <div class="page repo-page">
    <AIInlineChat
      v-if="view === 'overview'"
      class="ai-entry"
      :repo-options="repos"
      :username="auth.username"
    />

    <header class="page-head">
      <div class="head-copy">
        <h1>{{ pageTitle }}</h1>
        <p class="head-sub">
          共 <strong>{{ repos.length }}{{ hasNext ? '+' : '' }}</strong> 个仓库
          <template v-if="viewingOwn">
            · <strong>{{ openPrs.length }}{{ workspaceTruncated ? '+' : '' }}</strong> 个待处理 PR
          </template>
        </p>
      </div>

      <div class="head-actions">
        <el-input
          v-model="searchText"
          class="mobile-search"
          placeholder="搜索仓库或用户名"
          clearable
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="dialogVisible = true">
          <el-icon><Plus /></el-icon>
          <span>新建仓库</span>
        </el-button>
      </div>
    </header>

    <section v-if="view === 'pulls' || (view === 'overview' && viewingOwn)" class="list-card">
      <header class="card-head">
        <h2>待处理 PR</h2>
        <span class="card-count">{{ openPrs.length }}{{ workspaceTruncated ? '+' : '' }}</span>
        <span class="card-note">
          <template v-if="workspaceLoading">聚合中…</template>
          <template v-else-if="workspaceFailed">{{ workspaceFailed }} 个仓库未加载</template>
        </span>
        <button v-if="view === 'overview'" type="button" class="more-link" @click="goView('#pulls')">
          查看全部
          <el-icon><ArrowRight /></el-icon>
        </button>
      </header>

      <div class="table-head pr-head">
        <span>编号</span>
        <span>标题</span>
        <span>仓库</span>
        <span class="head-right">创建时间</span>
      </div>

      <div v-loading="workspaceLoading" class="rows">
        <button
          v-for="pr in visiblePrs"
          :key="`${pr.repoFullName}#${pr.id}`"
          type="button"
          class="list-row pr-row"
          @click="openPr(pr)"
        >
          <span class="pr-id mono">#{{ pr.id }}</span>
          <span class="pr-title">{{ pr.title }}</span>
          <span class="pr-repo">{{ pr.repoFullName }}</span>
          <span class="row-time">{{ formatDateTime(pr.createTime) }}</span>
        </button>

        <div v-if="!workspaceLoading && !openPrs.length" class="list-empty">
          <template v-if="workspaceAllFailed">
            <span>待办加载失败</span>
            <el-button size="small" @click="aggregateOpenPrs(repos)">重试</el-button>
          </template>
          <template v-else>当前页仓库没有待处理的 Pull Request</template>
        </div>
      </div>

      <p v-if="view === 'overview' && openPrs.length > overviewPrs.length" class="card-foot">
        仅展示最近 {{ overviewPrs.length }} 条
      </p>
    </section>

    <section v-if="view !== 'pulls'" class="list-card">
      <header class="card-head">
        <h2>仓库</h2>
        <span class="card-count">{{ repos.length }}{{ hasNext ? '+' : '' }}</span>
        <span class="card-note">按最近更新时间排列</span>
        <button v-if="view === 'overview'" type="button" class="more-link" @click="goView('#repos')">
          查看全部
          <el-icon><ArrowRight /></el-icon>
        </button>
      </header>

      <div class="table-head repo-head">
        <span>名称</span>
        <span>可见性</span>
        <span>默认分支</span>
        <span class="head-right">最近更新</span>
      </div>

      <div v-loading="loading" class="rows">
        <button
          v-for="row in visibleRepos"
          :key="`${row.ownerName}/${row.name}`"
          type="button"
          class="list-row repo-row"
          @click="openRepo(row)"
        >
          <span class="repo-cell">
            <span class="repo-line">
              <el-icon class="repo-icon"><Folder /></el-icon>
              <span class="repo-name">
                <span class="repo-owner">{{ row.ownerName }} /</span> {{ row.name }}
              </span>
            </span>
            <span class="repo-desc">{{ row.description || '暂无描述' }}</span>
          </span>
          <span class="repo-vis">
            <span class="dot" :class="row.visibility === 1 ? 'is-public' : 'is-private'" />
            {{ row.visibility === 1 ? '公开' : '私有' }}
          </span>
          <span class="repo-branch mono">
            <el-icon><Share /></el-icon>
            {{ row.defaultBranch || '-' }}
          </span>
          <span class="repo-time">{{ formatDateTime(row.updateTime) }}</span>
        </button>

        <div v-if="!loading && !repos.length" class="list-empty">
          <el-icon><FolderOpened /></el-icon>
          <span>{{ emptyHint }}</span>
        </div>
      </div>

      <div v-if="view === 'repos' && repos.length" class="card-foot pager">
        <el-button size="small" :disabled="loading || page === 1" @click="changePage(page - 1)">
          上一页
        </el-button>
        <span class="page-no">第 {{ page }} 页</span>
        <el-button size="small" :disabled="loading || !hasNext" @click="changePage(page + 1)">
          下一页
        </el-button>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" title="新建仓库" width="480px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="所有者">
          <el-input :model-value="auth.username" disabled />
        </el-form-item>
        <el-form-item label="仓库名" prop="repo">
          <el-input v-model="createForm.repo" placeholder="如 hello-gitlet" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="createForm.visibility">
            <el-radio :value="0">私有</el-radio>
            <el-radio :value="1">公开</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="自动 PR">
          <el-switch v-model="createForm.allowAutoPr" :active-value="1" :inactive-value="0" />
          <span class="muted hint">关闭后，推送到 feature 分支不再自动创建 PR</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 10px;
  font-size: 12px;
}

/* ============================================================
   页头：标题靠左，操作靠右，两端对齐
   ============================================================ */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}

/* 概览：标题行在 AI 对话框下方 */
.ai-entry + .page-head {
  margin-top: 28px;
}

.head-copy h1 {
  margin: 0;
  color: var(--text-0);
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.head-sub {
  margin: 5px 0 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.head-sub strong {
  color: var(--text-1);
  font-weight: 600;
}

.head-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.head-actions :deep(.el-button) {
  height: 34px;
}

.mobile-search {
  display: none;
  width: 200px;
}

/* ============================================================
   列表：无卡片，模块之间只靠一条极细的分割线
   ============================================================ */
.list-card {
  margin-top: 26px;
  padding-top: 20px;
  background: transparent;
}

/* 只有两个列表相接时才画分割线，避免和页头线叠成双线 */
.list-card + .list-card {
  border-top: 1px solid var(--line);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 12px;
}

.card-head h2 {
  margin: 0;
  color: var(--text-0);
  font-size: 13.5px;
  font-weight: 650;
}

.card-count {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--hover-2);
  color: var(--text-2);
  font-size: 11px;
  line-height: 18px;
}

.card-note {
  color: var(--text-3);
  font-size: 11.5px;
}

/* 次级入口：默认跟正文同色，hover 才提示可点 */
.more-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-2);
  font: inherit;
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
  transition: color 120ms var(--ease);
}

.more-link:hover {
  color: var(--accent-2);
  text-decoration: none;
}

.rows {
  min-height: 48px;
}

/* 表头与行共用同一套网格，保证列严格对齐 */
.table-head {
  display: grid;
  align-items: center;
  gap: 16px;
  margin: 0 -12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--table-head-bg);
  color: var(--text-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.head-right {
  justify-self: end;
  text-align: right;
}

.list-row {
  width: calc(100% + 24px);
  margin: 0 -12px;
  padding: 16px 12px;
  border: 0;
  border-bottom: 1px solid var(--line-soft);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 140ms var(--ease);
}

.list-row:last-child {
  border-bottom: 0;
}

.list-row:hover,
.list-row:focus-visible {
  background: var(--hover-2);
}

.list-row:focus-visible {
  outline: none;
}

/* ---------- 待处理 PR ---------- */
.pr-head,
.pr-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 200px 160px;
  align-items: center;
  gap: 16px;
}

.pr-id {
  color: var(--text-3);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.pr-title {
  min-width: 0;
  overflow: hidden;
  color: var(--text-0);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pr-repo {
  min-width: 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-time {
  justify-self: end;
  color: var(--text-muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ---------- 仓库 ---------- */
/* 名称 40% / 可见性 15% / 默认分支 15% / 最近更新 30% */
.repo-head,
.repo-row {
  display: grid;
  grid-template-columns: minmax(0, 4fr) minmax(0, 1.5fr) minmax(0, 1.5fr) minmax(0, 3fr);
  align-items: center;
  gap: 16px;
}

.repo-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.repo-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.repo-icon {
  flex: 0 0 auto;
  color: var(--accent-warn);
  font-size: 16px;
}

.repo-name {
  overflow: hidden;
  color: var(--text-0);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-owner {
  color: var(--text-2);
  font-weight: 400;
}

.repo-desc {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-vis {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-1);
  font-size: 12.5px;
}

.dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-3);
}

.dot.is-public {
  background: var(--accent-ok);
  box-shadow: 0 0 0 3px oklch(0.6 0.14 160 / 0.14);
}

.repo-branch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  color: var(--text-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-branch .el-icon {
  flex: 0 0 auto;
}

.repo-time {
  justify-self: end;
  color: var(--text-muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ---------- 空态 / 页脚 ---------- */
.list-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 96px;
  padding: 20px 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin: 0;
  padding: 12px 0 0;
  color: var(--text-3);
  font-size: 11.5px;
}

.card-foot.pager {
  justify-content: flex-end;
}

.page-no {
  color: var(--text-3);
  font-size: 12px;
}

@media (max-width: 960px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }

  .head-actions {
    justify-content: flex-end;
  }
}

@media (max-width: 860px) {
  .repo-head,
  .repo-row {
    grid-template-columns: minmax(0, 1fr) 96px;
  }

  .repo-head span:nth-child(n + 3),
  .repo-branch,
  .repo-time {
    display: none;
  }
}

@media (max-width: 720px) {
  .pr-head,
  .pr-row {
    grid-template-columns: 56px minmax(0, 1fr);
  }

  .pr-head span:nth-child(n + 3),
  .pr-repo,
  .row-time {
    display: none;
  }

  .table-head {
    padding: 8px 12px;
  }
}

@media (max-width: 560px) {
  .mobile-search {
    display: block;
    flex: 1;
    width: auto;
  }

  .list-row {
    padding: 12px 12px;
  }

  .card-head {
    padding: 0 0 12px;
  }
}
</style>
