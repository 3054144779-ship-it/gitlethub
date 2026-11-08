import http from './http'

const prBase = (owner, repo, prId) =>
  `/api/repos/${owner}/${repo}/pull-request/${prId}`

/** 顶级评论分页；回复已由后端挂在每条评论的 replies 上 */
export function listComments(owner, repo, prId, { commentType, pageNum = 1, pageSize = 10 } = {}) {
  return http.get(`${prBase(owner, repo, prId)}/comments`, {
    params: { comment_type: commentType, page_num: pageNum, page_size: pageSize }
  })
}

/** payload: { body, commentType, filePath, lineNumber, diffSide, commitSha, replyToId } */
export function createComment(owner, repo, prId, payload) {
  return http.post(`${prBase(owner, repo, prId)}/comments`, payload)
}

export function updateComment(owner, repo, prId, commentId, body) {
  return http.put(`${prBase(owner, repo, prId)}/comments/${commentId}`, { body })
}

export function deleteComment(owner, repo, prId, commentId) {
  return http.delete(`${prBase(owner, repo, prId)}/comments/${commentId}`)
}

export function listReviews(owner, repo, prId) {
  return http.get(`${prBase(owner, repo, prId)}/reviews`)
}

/** payload: { state: 0 仅评论 / 1 通过 / 2 请求修改, body, commitSha } */
export function createReview(owner, repo, prId, payload) {
  return http.post(`${prBase(owner, repo, prId)}/reviews`, payload)
}
