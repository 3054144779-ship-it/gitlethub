import { ref, watch } from 'vue'

const KEY = 'gitlethub.comment-display'

function readStored() {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

/** tree=递归树状（默认）；flat=同层平铺，用「A 回复 @B」表达关系 */
export const commentDisplayMode = ref(readStored() === 'flat' ? 'flat' : 'tree')

watch(commentDisplayMode, (mode) => {
  try {
    localStorage.setItem(KEY, mode)
  } catch {
    // 隐私模式等场景下 localStorage 不可写，忽略
  }
})
