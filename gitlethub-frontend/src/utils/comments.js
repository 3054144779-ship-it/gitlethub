/**
 * 把后端按 replyToId 组装的 replies 树展平成同一层级的对话流。
 * 返回 [{ comment, replyToName }]，replyToName 用于渲染「A 回复 @B」。
 */
export function flattenComments(list = []) {
  const names = new Map()

  const collect = (nodes) => {
    for (const node of nodes || []) {
      names.set(node.id, node.userName || '未知用户')
      collect(node.replies)
    }
  }
  collect(list)

  const result = []
  const walk = (nodes) => {
    for (const node of nodes || []) {
      result.push({
        comment: node,
        replyToName: node.replyToId == null ? '' : names.get(node.replyToId) || ''
      })
      walk(node.replies)
    }
  }
  walk(list)
  return result
}
