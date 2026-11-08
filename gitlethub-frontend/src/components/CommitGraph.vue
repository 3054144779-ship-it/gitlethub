<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRepo, listBranches, listCommits } from '../api/repo'
import { buildGraph } from '../utils/commitGraph'

const props = defineProps({
  owner: { type: String, required: true },
  repo: { type: String, required: true },
  /** 需要定位并高亮的提交 sha（从提交详情页跳过来时带上） */
  highlight: { type: String, default: '' }
})

const router = useRouter()

/**
 * 后端 CommitQueryServiceImpl.walkHistory 的 MAX_HISTORY：历史最多走 1000 个节点。
 * 这里一次把整张图取全（不能分页——边会跨页），超过就是被截断了。
 */
const MAX_HISTORY = 1000

/** 每行高度必须固定：SVG 里的节点坐标是按它算的，行高一变就对不齐 */
const ROW_HEIGHT = 36
const LANE_WIDTH = 16
const LANE_PAD = 12

const LANE_COLORS = ['#818cf8', '#4ade80', '#fbbf24', '#f87171', '#94a3b8', '#c084fc', '#2dd4bf', '#fb923c']

/** 作者为空的提交在筛选里单独归一类（空串已经被「全部」占用） */
const UNKNOWN_AUTHOR = '__unknown__'
/** 作者筛选项：空串 = 不筛选 */
const ALL_AUTHORS = ''

const branches = ref([])
const branch = ref('')
const loading = ref(false)
const error = ref('')
const truncated = ref(false)

/** 后端返回的完整提交列表（未过滤），过滤只影响显示，不改这份数据 */
const rawCommits = ref([])
/** 筛选后重新算出来的图 */
const nodes = ref([])
const edges = ref([])
const laneCount = ref(1)

const authorFilter = ref(ALL_AUTHORS)
const firstParentOnly = ref(false)
const hoveredRow = ref(null)
const locatedSha = ref('')
const locateMissed = ref(false)

const bodyRef = ref()

/** 可选作者：作者大多为 null（submission_record 只有 tip 有记录），单独给一项「未知」 */
const authorOptions = computed(() => {
  const seen = new Set()
  for (const c of rawCommits.value) {
    seen.add(c.authorName || UNKNOWN_AUTHOR)
  }
  const rest = [...seen].filter((k) => k !== UNKNOWN_AUTHOR).sort()
  const options = [{ label: '全部作者', value: ALL_AUTHORS }]
  for (const key of rest) {
    options.push({ label: key, value: key })
  }
  if (seen.has(UNKNOWN_AUTHOR)) {
    options.push({ label: '未知', value: UNKNOWN_AUTHOR })
  }
  return options
})

/**
 * 分支 tip。walkHistory 是先从 startSha（分支 tip）开始 DFS 的，所以返回列表的第一条就是 tip。
 */
const tipSha = computed(() => rawCommits.value[0]?.sha || null)

function authorKeyOf(commit) {
  return commit.authorName || UNKNOWN_AUTHOR
}

const filteredCommits = computed(() => {
  let list = rawCommits.value

  if (firstParentOnly.value) {
    // 只留第一父链：从 tip 沿 parent 一路走到底（第二父整条支线都不显示）
    const bySha = new Map(list.map((c) => [c.sha, c]))
    const chain = []
    const seen = new Set()
    let cursor = tipSha.value
    while (cursor && bySha.has(cursor) && !seen.has(cursor)) {
      seen.add(cursor)
      const commit = bySha.get(cursor)
      chain.push(commit)
      cursor = commit.parent
    }
    list = chain
  }

  if (authorFilter.value !== ALL_AUTHORS) {
    list = list.filter((c) => authorKeyOf(c) === authorFilter.value)
  }
  return list
})

const filtering = computed(() => firstParentOnly.value || authorFilter.value !== ALL_AUTHORS)

const graphWidth = computed(() => LANE_PAD * 2 + Math.max(1, laneCount.value) * LANE_WIDTH)
const graphHeight = computed(() => nodes.value.length * ROW_HEIGHT)
const rowPadding = computed(() => `${graphWidth.value + 10}px`)

/** sha -> 指向它的分支名，用来在图上打分支标签（CommitVO.branchName 只是 tip 的推送记录，不能当标签用） */
const branchTips = computed(() => {
  const map = new Map()
  for (const b of branches.value) {
    if (!b.commitSha) continue
    const names = map.get(b.commitSha)
    if (names) names.push(b.branchName)
    else map.set(b.commitSha, [b.branchName])
  }
  return map
})

/** 鼠标所在节点关联的边下标（进入它的和它出去的），其余边弱化 */
const hotEdges = computed(() => {
  const hot = new Set()
  if (hoveredRow.value === null) return hot
  edges.value.forEach((edge, index) => {
    if (edge.y1 === hoveredRow.value || edge.y2 === hoveredRow.value) hot.add(index)
  })
  return hot
})

function xOf(lane) {
  return LANE_PAD + lane * LANE_WIDTH + LANE_WIDTH / 2
}

function yOf(row) {
  return row * ROW_HEIGHT + ROW_HEIGHT / 2
}

function edgePath(edge) {
  const x1 = xOf(edge.x1)
  const y1 = yOf(edge.y1)
  const x2 = xOf(edge.x2)
  const y2 = yOf(edge.y2)
  const dy = Math.max(6, (y2 - y1) * 0.5)
  return `M ${x1} ${y1} C ${x1} ${y1 + dy}, ${x2} ${y2 - dy}, ${x2} ${y2}`
}

function edgeColor(edge, index) {
  return hoveredRow.value !== null && !hotEdges.value.has(index) ? 'rgba(148, 163, 184, 0.18)' : laneColor(edge.x1)
}

function laneColor(lane) {
  return LANE_COLORS[lane % LANE_COLORS.length]
}

function labelsOf(sha) {
  return branchTips.value.get(sha) || []
}

function openCommit(node) {
  router.push({ name: 'commit', params: { owner: props.owner, repo: props.repo, sha: node.sha } })
}

/** 重建图：过滤条件或原始数据变了就重跑，保证泳道是按「当前显示的那些提交」算的 */
function rebuild() {
  const graph = buildGraph(filteredCommits.value)
  nodes.value = graph.nodes
  edges.value = graph.edges
  laneCount.value = graph.laneCount
  locate()
}

/** 定位到 highlight 指定的提交：滚到那一行并高亮；不在当前图里就提示 */
async function locate() {
  locatedSha.value = ''
  locateMissed.value = false
  const target = props.highlight
  if (!target) return
  const found = nodes.value.find((node) => node.sha === target)
  if (!found) {
    locateMissed.value = true
    return
  }
  locatedSha.value = target
  await nextTick()
  bodyRef.value?.querySelector(`[data-sha="${target}"]`)?.scrollIntoView({ block: 'center' })
}

function resetFilters() {
  authorFilter.value = ALL_AUTHORS
  firstParentOnly.value = false
  rebuild()
}

watch(
  () => props.highlight,
  () => locate()
)

async function load() {
  if (!branch.value) {
    rawCommits.value = []
    nodes.value = []
    edges.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const commits = (await listCommits(props.owner, props.repo, {
      branch: branch.value,
      page: 1,
      size: MAX_HISTORY
    })) || []
    truncated.value = commits.length >= MAX_HISTORY
    rawCommits.value = commits
    // 换分支后旧的作者筛选可能筛不出东西，直接重置
    resetFilters()
    rebuild()
  } catch (e) {
    error.value = e?.message || '读取提交失败'
    rawCommits.value = []
    nodes.value = []
    edges.value = []
  } finally {
    loading.value = false
  }
}

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

onMounted(init)
</script>

<template>
  <div class="commit-graph">
    <div class="toolbar">
      <span class="muted">分支：</span>
      <el-select v-model="branch" placeholder="选择分支" size="small" :disabled="!branches.length" @change="load">
        <el-option v-for="b in branches" :key="b.branchName" :label="b.branchName" :value="b.branchName" />
      </el-select>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="toolbar">
      <el-select v-model="authorFilter" size="small" style="width: 150px" @change="rebuild">
        <el-option v-for="opt in authorOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-checkbox v-model="firstParentOnly" size="small" @change="rebuild">只看第一父链</el-checkbox>
      <el-button v-if="filtering" link size="small" @click="resetFilters">重置</el-button>
    </div>

    <el-alert
      v-if="truncated"
      type="warning"
      :closable="false"
      title="历史超过 1000 个提交，图已被截断"
      description="后端单次最多返回 1000 个节点，更早的提交和断掉的连线不会出现。"
    />

    <el-alert v-if="error" type="error" :closable="false" :title="error" />

    <el-alert
      v-if="locateMissed"
      type="info"
      :closable="false"
      title="要定位的提交不在当前分支的历史里"
      description="它可能属于别的分支，或已被 1000 个节点的上限截断。换一个分支再试。"
    />

    <el-alert
      v-else-if="filtering"
      type="info"
      :closable="false"
      title="筛选后连线可能断掉"
      description="被筛掉的提交不显示，连到它们的边会变成虚线小尾巴，这不代表历史真的断了。"
    />

    <div ref="bodyRef" v-loading="loading" class="graph-body">
      <el-empty v-if="!nodes.length" :image-size="60" description="没有提交（请先用 CLI 推送）" />
      <div v-else class="graph-rows">
        <svg class="graph-svg" :width="graphWidth" :height="graphHeight">
          <path
            v-for="(edge, i) in edges"
            :key="`e${i}`"
            :d="edgePath(edge)"
            :stroke="edgeColor(edge, i)"
            :stroke-dasharray="edge.dangling ? '3 3' : undefined"
            :stroke-width="hotEdges.has(i) ? 2.4 : 1.6"
            fill="none"
            stroke-linecap="round"
          />
          <circle
            v-for="node in nodes"
            :key="`glow${node.sha}`"
            :cx="xOf(node.lane)"
            :cy="yOf(node.row)"
            :r="node.secondParent ? 9 : 7"
            :fill="laneColor(node.lane)"
            opacity="0.16"
          />
          <circle
            v-for="node in nodes"
            :key="`n${node.sha}`"
            :cx="xOf(node.lane)"
            :cy="yOf(node.row)"
            :r="node.secondParent ? 4.5 : 3.5"
            :fill="laneColor(node.lane)"
            :stroke="node.secondParent ? '#94a3b8' : 'none'"
            stroke-width="1.5"
          />
        </svg>

        <div
          v-for="node in nodes"
          :key="node.sha"
          class="graph-row"
          :class="{ 'is-located': node.sha === locatedSha }"
          :data-sha="node.sha"
          :style="{ paddingLeft: rowPadding, height: `${ROW_HEIGHT}px` }"
          :title="`${node.sha}\n${node.message}`"
          @mouseenter="hoveredRow = node.row"
          @mouseleave="hoveredRow = null"
          @click="openCommit(node)"
        >
          <span class="row-sha mono">{{ node.sha.slice(0, 8) }}</span>
          <span class="row-message">{{ node.message }}</span>
          <span v-for="name in labelsOf(node.sha)" :key="name" class="row-branch">{{ name }}</span>
          <span v-if="node.secondParent" class="row-merge">merge</span>
          <span class="row-meta muted">{{ node.authorName || '未知' }} · {{ node.time }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.graph-body {
  min-height: 160px;
  /* 图可以上千行，必须自己滚，否则会把整页撑得极长 */
  max-height: 70vh;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-inset);
  padding: 8px 0;
}

.graph-rows {
  position: relative;
}

.graph-svg {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
}

.graph-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 10px;
  cursor: pointer;
  font-size: 13px;
}

.graph-row:hover {
  background: rgba(99, 102, 241, 0.1);
}

.graph-row.is-located {
  background: rgba(251, 191, 36, 0.12);
  box-shadow: inset 3px 0 0 var(--accent-warn);
}

.row-sha {
  color: var(--accent-2);
  flex: 0 0 auto;
}

.row-message {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row-branch {
  flex: 0 0 auto;
  padding: 0 7px;
  border-radius: 10px;
  background: rgba(99, 102, 241, 0.16);
  border: 1px solid rgba(99, 102, 241, 0.34);
  color: var(--accent-2);
  font-size: 12px;
  line-height: 18px;
}

.row-merge {
  flex: 0 0 auto;
  padding: 0 7px;
  border-radius: 10px;
  background: rgba(251, 191, 36, 0.14);
  border: 1px solid rgba(251, 191, 36, 0.3);
  color: var(--accent-warn);
  font-size: 12px;
  line-height: 18px;
}

.row-meta {
  flex: 0 0 auto;
  font-size: 12px;
}
</style>
