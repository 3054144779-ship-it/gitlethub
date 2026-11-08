import http from './http'

/**
 * PR 列表。status 传数字：0 open / 1 closed / 2 merged，不传（undefined）表示全部。
 * 注意读回来的 status 是枚举名（"OPEN"/"CLOSED"/"MERGED"），前端展示时两种写法都要兼容。
 */
export function listPullRequests(owner, repo, { status, page = 1, size = 20, silent = false } = {}) {
  return http.get(`/api/repos/${owner}/${repo}/pull-requests`, {
    params: { status, page, size },
    silent
  })
}

export function getPullRequest(owner, repo, id) {
  return http.get(`/api/repos/${owner}/${repo}/pull-request/${id}`)
}

/** PR 结构化差异：按文件分组，每行带新旧行号，供行级评论定位 */
export function getPullRequestDiff(owner, repo, id) {
  return http.get(`/api/repos/${owner}/${repo}/pull-request/${id}/diff`)
}

/** 创建 PR：{title, description, sourceBranch, targetBranch} */
export function createPullRequest(owner, repo, payload) {
  return http.post(`/api/repos/${owner}/${repo}/pull-request`, payload)
}

/** 合并 PR */
export function mergePullRequest(owner, repo, id) {
  return http.post(`/api/repos/${owner}/${repo}/pull-request/${id}`)
}

/** 关闭 PR */
export function closePullRequest(owner, repo, id) {
  return http.post(`/api/repos/${owner}/${repo}/pull-request/${id}/close`)
}

/**
 * 读取合并冲突暂存区。
 * 返回 BlobVO[]：path 是仓库内的相对路径，content 为文本内容；
 * isBinary 为 true 时 content 为空，不支持在线编辑。
 */
export function getStage(owner, repo, id) {
  return http.get(`/api/repos/${owner}/${repo}/stage`, {
    params: { pullRequestId: id }
  })
}

/**
 * 保存冲突解决结果（仅仓库 OWNER）。
 * blobs: [{ path, content }]，path 必须是暂存区里已存在的冲突文件。
 */
export function modifyStage(owner, repo, id, blobs) {
  return http.put(`/api/repos/${owner}/${repo}/modify/stage`, blobs, {
    params: { pullRequestId: id }
  })
}
