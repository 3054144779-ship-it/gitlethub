import http from './http'

const base = (owner, repo) => `/api/repos/${owner}/${repo}/tags`

export function listTags(owner, repo) {
  return http.get(base(owner, repo))
}

/** payload: { tagName, commitSha, description } */
export function createTag(owner, repo, payload) {
  return http.post(base(owner, repo), payload)
}

export function deleteTag(owner, repo, tagName) {
  return http.delete(`${base(owner, repo)}/${encodeURIComponent(tagName)}`)
}
