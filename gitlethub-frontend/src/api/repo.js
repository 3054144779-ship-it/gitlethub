import http from './http'

/** 当前用户的仓库列表。后端 keyword 是必填 request param，所以始终带上（可为空串） */
export function listRepos({ owner, keyword = '', page = 1, size = 20 }) {
  return http.get('/api/repos', { params: { owner, keyword, page, size } })
}

/** 仓库详情（前端路由 /:owner/:repo 用） */
export function getRepo(owner, repo) {
  return http.get(`/api/repos/${owner}/${repo}`)
}

/** 创建仓库：{owner, repo, description, visibility, allowAutoPr} */
export function createRepo(payload) {
  return http.post('/api/repos', payload)
}

/** 更新仓库配置（同创建 DTO） */
export function updateRepo(payload) {
  return http.put('/api/repos', payload)
}

/** 分支列表 */
export function listBranches(owner, repo) {
  return http.get(`/api/repos/${owner}/${repo}/branches`)
}

/** 提交历史（分支缺省用仓库默认分支） */
export function listCommits(owner, repo, { branch, page = 1, size = 20 } = {}) {
  return http.get(`/api/repos/${owner}/${repo}/commits`, { params: { branch, page, size } })
}

export function getCommit(owner, repo, sha) {
  return http.get(`/api/repos/${owner}/${repo}/commits/${sha}`)
}

/** 与第一父提交的差异 */
export function getCommitDiff(owner, repo, sha) {
  return http.get(`/api/repos/${owner}/${repo}/commits/${sha}/diff`)
}

/** 文件树（ref 可以是分支名或 commit sha） */
export function getTree(owner, repo, ref) {
  return http.get(`/api/repos/${owner}/${repo}/tree`, { params: { ref } })
}

/** 单个文件内容（二进制时 isBinary 为 true、content 为空串） */
export function getBlob(owner, repo, ref, path) {
  return http.get(`/api/repos/${owner}/${repo}/blob`, { params: { ref, path } })
}
