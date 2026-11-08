/**
 * 后端枚举经 Jackson 序列化后是「枚举名」字符串（PRStatusEnum 没有 @JsonValue）：
 *   status  -> "OPEN" | "CLOSED" | "MERGED"
 *   level   -> "OWNER" | "CONTRIBUTOR" | "GUEST"
 * 但作为请求参数时用的是数字 code（PR 查询 status=0/1/2；添加协作者 level=2/1/0）。
 * 这里两种写法都兼容。
 */

const PR_STATUS = {
  OPEN: { label: '开放', type: 'success', code: 0 },
  CLOSED: { label: '已关闭', type: 'info', code: 1 },
  MERGED: { label: '已合并', type: 'warning', code: 2 }
}

export function prStatusLabel(status) {
  return PR_STATUS[normalizePrStatus(status)]?.label ?? '未知'
}

export function prStatusTagType(status) {
  return PR_STATUS[normalizePrStatus(status)]?.type ?? 'info'
}

export function normalizePrStatus(status) {
  if (status === 0 || status === '0') return 'OPEN'
  if (status === 1 || status === '1') return 'CLOSED'
  if (status === 2 || status === '2') return 'MERGED'
  return status
}

const LEVEL = {
  OWNER: { label: '所有者', type: 'danger', code: 2 },
  CONTRIBUTOR: { label: '贡献者', type: 'primary', code: 1 },
  GUEST: { label: '访客', type: 'info', code: 0 }
}

export function levelLabel(level) {
  return LEVEL[normalizeLevel(level)]?.label ?? '未知'
}

export function levelTagType(level) {
  return LEVEL[normalizeLevel(level)]?.type ?? 'info'
}

export function normalizeLevel(level) {
  if (level === 2 || level === '2') return 'OWNER'
  if (level === 1 || level === '1') return 'CONTRIBUTOR'
  if (level === 0 || level === '0') return 'GUEST'
  return level
}

/** 可授予的等级（不含 OWNER） */
export const GRANTABLE_LEVELS = [
  { value: 1, label: '贡献者' },
  { value: 0, label: '访客' }
]

/** 提交 diff 的变更类型 → 展示色 */
export function changeTypeTagType(changeType) {
  if (changeType === 'ADDED') return 'success'
  if (changeType === 'DELETED') return 'danger'
  return 'warning'
}
