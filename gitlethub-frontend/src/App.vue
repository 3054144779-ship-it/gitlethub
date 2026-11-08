<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getUser } from './api/user'
import { useAuthStore } from './stores/auth'
import { applyTheme, getTheme } from './utils/theme'
import ThemeToggle from './components/ThemeToggle.vue'
import AIFloatingChat from './components/AIFloatingChat.vue'
import BrandMark from './components/BrandMark.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const theme = ref(getTheme())
const isDark = computed(() => theme.value === 'dark')

function toggleTheme() {
  theme.value = applyTheme(isDark.value ? 'light' : 'dark')
}

const showHeader = computed(() => route.name !== 'login' && route.name !== 'register')
const initial = computed(() => (auth.username || '?').slice(0, 1).toUpperCase())
const avatarUrl = computed(() => auth.avatar || '')
const email = ref('')

async function syncUser() {
  if (!auth.userId) {
    email.value = ''
    return
  }
  try {
    const user = await getUser(auth.userId)
    auth.setAvatar(user?.avatar)
    email.value = user?.email || ''
  } catch {
    // 资料同步失败不影响页面，错误提示已由 http 拦截器统一弹出
  }
}

onMounted(syncUser)
watch(() => auth.userId, syncUser)
// 资料页可能改了邮箱，离开时重新同步一次
watch(
  () => route.name,
  (name, previous) => {
    if (previous === 'profile') syncUser()
  }
)

const navItems = computed(() => [
  {
    key: 'overview',
    label: '概览',
    icon: 'HomeFilled',
    to: { name: 'repos' },
    active: route.name === 'repos' && !route.hash
  },
  {
    key: 'repos',
    label: '仓库',
    icon: 'Folder',
    to: { name: 'repos', hash: '#repos' },
    active: route.name === 'repos' && route.hash === '#repos'
  },
  {
    key: 'pulls',
    label: '待处理 PR',
    icon: 'Bell',
    to: { name: 'repos', hash: '#pulls' },
    active: route.name === 'repos' && route.hash === '#pulls'
  },
  { key: 'issues', label: 'Issues', icon: 'ChatLineSquare', disabled: true },
  {
    key: 'profile',
    label: '个人资料',
    icon: 'User',
    to: { name: 'profile' },
    active: route.name === 'profile'
  },
  { key: 'settings', label: '设置', icon: 'Setting', disabled: true }
])

/** Windows 客户端安装包，存放在阿里云 OSS；该对象需设置为公共读 */
const CLIENT_DOWNLOAD_URL =
  'https://huahuo-tlias.oss-cn-beijing.aliyuncs.com/gitlet-service/gitlet-1.0.msi'

const globalSearch = ref('')

function goItem(item) {
  if (item.disabled) return
  router.push(item.to)
}

function submitGlobalSearch() {
  const keyword = globalSearch.value.trim()
  router.push({ name: 'repos', query: keyword ? { q: keyword } : {} })
}

function goHome() {
  router.push({ name: 'repos' })
}

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div v-if="!showHeader" class="auth-shell">
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
  </div>

  <div v-else class="app-shell">
    <header class="app-header">
      <div class="header-brand">
        <div class="brand" role="button" tabindex="0" @click="goHome" @keyup.enter="goHome">
          <BrandMark :size="26" />
          <span class="brand-text"><span class="brand-name">Gitlet</span><span class="brand-name strong">Hub</span></span>
        </div>
      </div>

      <div class="header-search">
        <el-input
          v-model="globalSearch"
          placeholder="搜索仓库或用户名；支持 用户名/仓库名"
          clearable
          @keyup.enter="submitGlobalSearch"
          @clear="submitGlobalSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <div class="header-right">
        <AIFloatingChat />
        <ThemeToggle class="nav-theme" :dark="isDark" @toggle="toggleTheme" />
        <a
          class="client-download"
          :href="CLIENT_DOWNLOAD_URL"
          title="下载 gitlet 客户端（Windows）"
          aria-label="下载 gitlet 客户端"
        >
          <el-icon><Download /></el-icon>
          <span class="client-download-text">客户端</span>
        </a>
        <!-- 窄屏没有侧栏，用户与退出入口留在顶栏 -->
        <div class="header-user">
          <el-button class="user-btn" text @click="router.push({ name: 'profile' })">
            <span class="avatar">
              <img v-if="avatarUrl" :src="avatarUrl" alt="" />
              <template v-else>{{ initial }}</template>
            </span>
            <span class="user-name">{{ auth.username }}</span>
          </el-button>
          <el-button class="icon-btn" text title="退出登录" @click="logout">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </div>
      </div>
    </header>

    <div class="app-body">
      <aside class="app-side">
        <nav class="side-nav" aria-label="主导航">
          <p class="side-label">导航</p>
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="side-item"
            :class="{ 'is-active': item.active, 'is-disabled': item.disabled }"
            :aria-current="item.active ? 'page' : undefined"
            :title="item.disabled ? '尚未实现' : item.label"
            @click="goItem(item)"
          >
            <el-icon class="side-icon"><component :is="item.icon" /></el-icon>
            <span class="side-text">{{ item.label }}</span>
            <span v-if="item.disabled" class="soon">即将</span>
          </button>
        </nav>

        <div class="side-foot">
          <button
            type="button"
            class="side-user"
            title="个人资料"
            @click="router.push({ name: 'profile' })"
          >
            <span class="avatar">
              <img v-if="avatarUrl" :src="avatarUrl" alt="" />
              <template v-else>{{ initial }}</template>
            </span>
            <span class="side-user-meta">
              <span class="side-user-name">{{ auth.username || '未登录' }}</span>
              <span v-if="email" class="side-user-sub">{{ email }}</span>
            </span>
          </button>
          <el-button class="side-logout" text title="退出登录" @click="logout">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </div>
      </aside>

      <main class="app-main">
        <nav class="mobile-nav" aria-label="主导航">
          <button
            v-for="item in navItems.filter((entry) => !entry.disabled)"
            :key="item.key"
            type="button"
            class="mobile-nav-item"
            :class="{ 'is-active': item.active }"
            @click="goItem(item)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </nav>

        <router-view v-slot="{ Component, route: current }">
          <transition name="page" mode="out-in">
            <component :is="Component" :key="current.path" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--bg-0);
}

/* ---------- 顶栏 ---------- */
.app-header {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 16px;
  height: 56px;
  padding: 0 20px;
  background: var(--header-bg);
  border-bottom: 1px solid var(--line);
  box-shadow: var(--header-shadow);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.header-brand {
  flex: 0 0 212px;
  min-width: 0;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  user-select: none;
}

.brand-text {
  display: inline-flex;
  align-items: baseline;
  font-family: 'Space Grotesk', 'Inter', 'Segoe UI Variable Display', 'Segoe UI', system-ui,
    sans-serif;
  font-size: 15.5px;
  letter-spacing: -0.015em;
  line-height: 1;
}

.brand-name {
  color: var(--text-1);
  font-weight: 400;
}

.brand-name.strong {
  color: var(--text-0);
  font-weight: 700;
}

.header-search {
  flex: 1 1 auto;
  max-width: 520px;
  min-width: 0;
  margin-left: auto;
}

.header-search :deep(.el-input__wrapper) {
  height: 34px;
  padding: 0 10px;
  background: var(--bg-1);
  border-radius: 7px;
  box-shadow: inset 0 0 0 1px var(--line);
}

.header-search :deep(.el-input__wrapper.is-focus) {
  background: var(--surface-1);
  box-shadow: inset 0 0 0 1px var(--focus-line);
}

.header-search :deep(.el-input__inner) {
  font-size: 13px;
}

.header-right {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

/* 客户端下载：与顶栏其他文字按钮同高同色，只多一个图标 */
.client-download {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 8px;
  border-radius: 7px;
  color: var(--text-2);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
  transition: color 120ms var(--ease), background-color 120ms var(--ease);
}

.client-download:hover {
  color: var(--text-0);
  background: var(--hover-1);
  text-decoration: none;
}

.client-download .el-icon {
  font-size: 15px;
}

/* 宽屏的用户入口在侧栏底部，这里只留给窄屏 */
.header-user {
  display: none;
  align-items: center;
  gap: 4px;
}

.user-btn.el-button,
.icon-btn.el-button {
  margin-left: 0;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: 7px;
  color: var(--text-2);
  background: transparent;
}

.user-btn.el-button:hover,
.icon-btn.el-button:hover {
  color: var(--text-0);
  background: var(--hover-1);
}

.icon-btn.el-button {
  width: 32px;
  padding: 0;
}

.user-name {
  max-width: 140px;
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-right: 7px;
  border-radius: 50%;
  overflow: hidden;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: var(--accent-1);
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* ---------- 侧边导航 + 内容 ---------- */
.app-body {
  display: flex;
  align-items: flex-start;
  min-height: calc(100vh - 56px);
}

.app-side {
  position: sticky;
  top: 56px;
  display: flex;
  flex: 0 0 232px;
  flex-direction: column;
  width: 232px;
  height: calc(100vh - 56px);
  padding: 14px 10px 16px;
  border-right: 1px solid var(--line);
  background: var(--bg-0);
  overflow-y: auto;
}

.side-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.side-label {
  margin: 4px 10px 8px;
  color: var(--text-3);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.side-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-2);
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background-color 120ms var(--ease), color 120ms var(--ease);
}

.side-item:hover {
  background: var(--hover-1);
  color: var(--text-0);
}

.side-item.is-active {
  background: var(--hover-2);
  color: var(--text-0);
  font-weight: 600;
}

.side-icon {
  flex: 0 0 auto;
  font-size: 15px;
  color: var(--text-3);
}

.side-item.is-active .side-icon {
  color: var(--accent-1);
}

.side-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soon {
  flex: 0 0 auto;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--hover-2);
  color: var(--text-3);
  font-size: 10px;
  font-weight: 500;
}

.side-item.is-disabled {
  cursor: default;
  opacity: 0.62;
}

.side-item.is-disabled:hover {
  background: transparent;
  color: var(--text-2);
}

.side-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
  padding: 10px 4px 0;
  border-top: 1px solid var(--line);
}

.side-user {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 9px;
  min-width: 0;
  padding: 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 120ms var(--ease);
}

.side-user:hover {
  background: var(--hover-1);
}

.side-user .avatar {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  margin: 0;
  font-size: 11px;
}

.side-user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.side-user-name {
  overflow: hidden;
  color: var(--text-0);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-user-sub {
  overflow: hidden;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-logout.el-button {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 7px;
  color: var(--text-3);
  background: transparent;
}

.side-logout.el-button:hover,
.side-logout.el-button:focus {
  color: var(--accent-danger);
  background: var(--hover-1);
}

.app-main {
  flex: 1;
  min-width: 0;
}

.mobile-nav {
  display: none;
}

.auth-shell {
  min-height: 100vh;
}

@media (max-width: 960px) {
  .app-side {
    display: none;
  }

  .header-user {
    display: flex;
  }

  .header-brand {
    flex: 0 0 auto;
  }

  .mobile-nav {
    position: sticky;
    top: 56px;
    z-index: 20;
    display: flex;
    gap: 4px;
    padding: 8px 12px;
    overflow-x: auto;
    background: var(--bg-0);
    border-bottom: 1px solid var(--line);
    scrollbar-width: none;
  }

  .mobile-nav::-webkit-scrollbar {
    display: none;
  }

  .mobile-nav-item {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 6px;
    height: 30px;
    padding: 0 10px;
    border: 0;
    border-radius: 7px;
    background: transparent;
    color: var(--text-2);
    font: inherit;
    font-size: 12.5px;
    cursor: pointer;
    white-space: nowrap;
  }

  .mobile-nav-item.is-active {
    background: var(--hover-2);
    color: var(--text-0);
    font-weight: 600;
  }
}

@media (max-width: 560px) {
  .app-header {
    gap: 10px;
    padding: 0 12px;
  }

  .header-search {
    display: none;
  }

  .user-name {
    display: none;
  }

  .user-btn.el-button {
    padding: 0 4px;
  }

  .client-download {
    justify-content: center;
    width: 32px;
    padding: 0;
  }

  .client-download-text {
    display: none;
  }
}
</style>
