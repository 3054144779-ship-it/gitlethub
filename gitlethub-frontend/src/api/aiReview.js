import http from './http'

const base = (owner, repo) => `/api/repos/${owner}/${repo}/ai-reviews`

/** 发起审查：{ pullRequestId } 与 { commitSha } 二选一 */
export function createAiReview(owner, repo, payload) {
  return http.post(base(owner, repo), payload)
}

export function listAiReviews(owner, repo, { pullRequestId, commitSha, page = 1, size = 5 } = {}) {
  return http.get(base(owner, repo), {
    params: { pull_request_id: pullRequestId, commit_sha: commitSha, page, size }
  })
}

export function getAiReview(owner, repo, recordId) {
  return http.get(`${base(owner, repo)}/${recordId}`)
}
