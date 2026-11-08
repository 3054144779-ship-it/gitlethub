import http from './http'

/** 协作者列表（后端已 join 用户名） */
export function listCollaborators(owner, repo) {
  return http.get(`/api/repos/${owner}/${repo}/collaborators`)
}

/** 添加协作者：level 传数字 1=贡献者, 0=访客（2=所有者不允许通过该接口授予） */
export function addCollaborator(owner, repo, { username, level }) {
  return http.post(`/api/repos/${owner}/${repo}/collaborators`, { username, level })
}

export function removeCollaborator(owner, repo, userId) {
  return http.delete(`/api/repos/${owner}/${repo}/collaborators/${userId}`)
}

/**
 * 更新当前登录用户在「指定仓库」的「自动创建 PR」开关，只对该仓库生效。
 * userId 由后端从 JWT 取，前端只需传 owner/repo 与开关本身。
 */
export function updateAutoCreatePr(owner, repo, autoCreatePr) {
  return http.put(`/api/repos/${owner}/${repo}/collaborators`, null, {
    params: { auto_create_pr: autoCreatePr }
  })
}
