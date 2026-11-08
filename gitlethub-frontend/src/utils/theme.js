const STORAGE_KEY = 'gitlethub-theme'
const THEMES = new Set(['dark', 'light'])

export function getTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (THEMES.has(saved)) return saved
  } catch {
    // 隐私模式下 localStorage 不可用，退回默认深色
  }
  return 'dark'
}

export function applyTheme(theme) {
  const next = THEMES.has(theme) ? theme : 'dark'
  const root = document.documentElement
  root.classList.toggle('dark', next === 'dark')
  root.dataset.theme = next
  // 与 index.html 的首屏脚本保持一致，避免地址栏/外围底色和页面不一致
  const color = next === 'dark' ? '#0b0f19' : '#f5f7fb'
  root.style.backgroundColor = color
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', color)
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 存不下也不影响本次会话
  }
  return next
}

export function initTheme() {
  return applyTheme(getTheme())
}
