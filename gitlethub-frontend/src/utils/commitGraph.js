/**
 * 提交图的纯计算部分：把后端返回的扁平提交列表还原成带泳道坐标的 DAG。
 * 与渲染无关，坐标单位是「行 / 泳道」，由调用方换算成像素。
 */

const MONTHS = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 }
const CLI_TIME_RE = /^\w{3} (\w{3}) (\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4}) ([+-]\d{4})$/

/**
 * 解析 gitlet 的提交时间。格式来自 SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US)
 * （见 gitlet_core/commit/Commit.java:38），例如 "Mon Sep 21 13:22:00 2026 +0800"。
 * 不是 ISO，所以不用 new Date()——那套宽松解析依赖浏览器实现，不可靠。
 * @param {string} text 提交时间
 * @returns {number} 毫秒时间戳；解析失败返回 0
 */
export function parseCliTime(text) {
  const m = CLI_TIME_RE.exec(text || '')
  if (!m) return 0
  const [, mon, day, hh, mm, ss, year, zone] = m
  const month = MONTHS[mon]
  if (month === undefined) return 0
  const offsetMinutes = (zone[0] === '-' ? -1 : 1) * (Number(zone.slice(1, 3)) * 60 + Number(zone.slice(3, 5)))
  return Date.UTC(Number(year), month, Number(day), Number(hh), Number(mm), Number(ss)) - offsetMinutes * 60000
}

/** 父提交列表：第一父 + 第二父（只有合并提交才有第二父） */
export function parentsOf(commit) {
  return [commit.parent, commit.secondParent].filter(Boolean)
}

/**
 * 计算图的节点顺序与泳道。
 * 排序用拓扑序（子在前、父在后），同一层里时间新的靠前；泳道按「谁在等这个 sha」就近复用，
 * 合并时斜线并过去（类似 GitHub 的默认视图）。
 *
 * @param {Array} commits 后端的 CommitVO 列表（不要求有序）
 * @returns {{nodes: Array, edges: Array, laneCount: number}}
 *   nodes: 按行排好的提交，附加 row / lane
 *   edges: {x1,y1,x2,y2,dangling}，坐标是泳道 / 行号；dangling 表示父提交不在本次返回集里
 */
export function buildGraph(commits) {
  const nodeBySha = new Map()
  for (const c of commits) {
    nodeBySha.set(c.sha, { ...c, ts: parseCliTime(c.time), row: 0, lane: 0 })
  }

  // 入度 = 集合内有多少个子提交指向它；零入度的就是各个分支的 tip
  const indegree = new Map()
  for (const c of commits) indegree.set(c.sha, 0)
  for (const c of commits) {
    for (const p of parentsOf(c)) {
      if (nodeBySha.has(p)) indegree.set(p, indegree.get(p) + 1)
    }
  }

  const ready = commits.filter((c) => indegree.get(c.sha) === 0).map((c) => c.sha)
  const ordered = []
  const emitted = new Set()
  while (ready.length) {
    let pick = 0
    for (let i = 1; i < ready.length; i++) {
      if (nodeBySha.get(ready[i]).ts > nodeBySha.get(ready[pick]).ts) pick = i
    }
    const sha = ready.splice(pick, 1)[0]
    if (emitted.has(sha)) continue
    emitted.add(sha)
    ordered.push(sha)
    for (const p of parentsOf(nodeBySha.get(sha))) {
      if (!nodeBySha.has(p)) continue
      const left = indegree.get(p) - 1
      indegree.set(p, left)
      if (left === 0) ready.push(p)
    }
  }
  // 兜底：对象缺失 / 异常数据导致拓扑序走不完时，剩下的按时间倒序接在后面
  if (ordered.length < commits.length) {
    const rest = commits.filter((c) => !emitted.has(c.sha))
    rest.sort((a, b) => nodeBySha.get(b.sha).ts - nodeBySha.get(a.sha).ts)
    ordered.push(...rest.map((c) => c.sha))
  }

  // 泳道：每条泳道记住自己「正在等待」的 sha，提交轮到时就占用等待它的泳道
  const lanes = []
  let maxLane = 0
  ordered.forEach((sha, row) => {
    const node = nodeBySha.get(sha)
    node.row = row

    let lane = lanes.indexOf(sha)
    if (lane === -1) {
      lane = lanes.indexOf(null)
      if (lane === -1) lane = lanes.length
      lanes[lane] = sha
    }
    node.lane = lane
    maxLane = Math.max(maxLane, lane)

    const parents = parentsOf(node).filter((p) => nodeBySha.has(p))
    if (!parents.length) {
      lanes[lane] = null // 根提交，这条泳道到头
    } else {
      // 第一父：若已有别的泳道在等它，本泳道就空出来，边斜过去（合并的可视化）
      lanes[lane] = lanes.indexOf(parents[0]) === -1 ? parents[0] : null
      // 第二父（合并提交）：复用已在等它的泳道，没有就新开一条
      for (let i = 1; i < parents.length; i++) {
        if (lanes.indexOf(parents[i]) !== -1) continue
        let target = lanes.indexOf(null)
        if (target === -1) target = lanes.length
        lanes[target] = parents[i]
      }
    }
    // 只裁尾部空泳道：不影响已有泳道下标，避免图越长越宽
    while (lanes.length && lanes[lanes.length - 1] === null) lanes.pop()
  })

  const edges = []
  for (const sha of ordered) {
    const node = nodeBySha.get(sha)
    for (const p of parentsOf(node)) {
      const parent = nodeBySha.get(p)
      if (parent) {
        edges.push({ x1: node.lane, y1: node.row, x2: parent.lane, y2: parent.row, dangling: false })
      } else {
        // 父提交不在返回集里（被截断，或对象缺失）：画一小截虚线示意历史到这里断了
        edges.push({ x1: node.lane, y1: node.row, x2: node.lane, y2: node.row + 0.6, dangling: true })
      }
    }
  }

  return { nodes: ordered.map((sha) => nodeBySha.get(sha)), edges, laneCount: maxLane + 1 }
}
