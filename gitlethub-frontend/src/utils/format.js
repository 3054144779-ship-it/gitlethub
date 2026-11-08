/** 后端 LocalDateTime 序列化为 ISO 串，页面统一展示到秒 */
export function formatDateTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

export function shortSha(sha, length = 8) {
  if (!sha) return '-'
  return sha.length > length ? sha.slice(0, length) : sha
}
