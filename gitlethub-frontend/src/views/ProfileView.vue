<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getAvatarUploadUrl, getUser, updateProfile } from '../api/user'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from '../utils/format'

const AVATAR_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']
const AVATAR_MAX_SIZE = 5 * 1024 * 1024

const auth = useAuthStore()
const router = useRouter()
const profile = ref(null)
const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const uploading = ref(false)
const fileInput = ref(null)

const form = reactive({
  email: '',
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const initials = computed(() => (auth.username || '?').slice(0, 1).toUpperCase())
const avatarUrl = computed(() => profile.value?.avatar || '')

// 令牌默认打码，只在用户主动点击眼睛时展开
const tokenVisible = ref(false)
const maskedToken = computed(() => {
  const token = auth.token
  if (!token) return '无'
  return `${token.slice(0, 8)}••••••${token.slice(-4)}`
})
const shownToken = computed(() => (tokenVisible.value ? auth.token : maskedToken.value))

async function copyToken() {
  if (!auth.token) return
  try {
    await navigator.clipboard.writeText(auth.token)
    ElMessage.success('令牌已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

function fillForm(data) {
  form.email = data?.email || ''
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}

async function load() {
  if (!auth.userId) return
  loading.value = true
  try {
    profile.value = await getUser(auth.userId)
    auth.setAvatar(profile.value?.avatar)
    if (!editing.value) fillForm(profile.value)
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

function startEdit() {
  fillForm(profile.value)
  editing.value = true
}

function cancelEdit() {
  fillForm(profile.value)
  editing.value = false
}

function pickAvatar() {
  if (uploading.value || loading.value) return
  fileInput.value?.click()
}

async function onAvatarSelected(event) {
  const file = event.target.files?.[0]
  // 清空 value，选同一个文件也能再次触发 change
  event.target.value = ''
  if (!file) return

  if (!AVATAR_TYPES.includes(file.type)) {
    ElMessage.warning('头像仅支持 PNG / JPEG / WebP / GIF')
    return
  }
  if (file.size > AVATAR_MAX_SIZE) {
    ElMessage.warning('头像大小不能超过 5MB')
    return
  }

  uploading.value = true
  try {
    // 后端按「uuid_文件名」生成对象名，这里先去掉可能影响 URL 的字符
    const safeName = file.name.replace(/[^\w.\-]+/g, '_')
    let presigned
    try {
      presigned = await getAvatarUploadUrl(safeName, file.type)
    } catch {
      // 错误提示由 http 拦截器统一处理
      return
    }

    // 预签名把 Content-Type 算进了签名，PUT 时必须发完全相同的值
    const contentType = presigned.contentType || file.type
    try {
      const response = await fetch(presigned.uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': contentType }
      })
      if (!response.ok) {
        throw new Error(
          response.status === 403
            ? '对象存储拒绝上传（403）：签名与 Content-Type 不匹配，或存储桶 CORS 未允许该请求头'
            : `上传失败（HTTP ${response.status}）`
        )
      }
    } catch (error) {
      ElMessage.error(
        error?.message || '直传对象存储失败，请确认存储桶已允许跨域 PUT'
      )
      return
    }

    try {
      const updated = await updateProfile({
        email: profile.value?.email || '',
        avatar: presigned.accessUrl
      })
      profile.value = updated || { ...profile.value, avatar: presigned.accessUrl }
      auth.setAvatar(profile.value?.avatar)
      ElMessage.success('头像已更新')
    } catch {
      // 错误提示由 http 拦截器统一处理
    }
  } finally {
    uploading.value = false
  }
}

async function save() {
  const email = form.email.trim()
  const newPassword = form.newPassword.trim()

  if (newPassword) {
    if (!form.oldPassword) {
      ElMessage.warning('修改密码需要填写原密码')
      return
    }
    if (newPassword.length < 6 || newPassword.length > 64) {
      ElMessage.warning('新密码长度需在 6 到 64 位之间')
      return
    }
    if (newPassword !== form.confirmPassword.trim()) {
      ElMessage.warning('两次输入的新密码不一致')
      return
    }
  }

  if (email === (profile.value?.email || '') && !newPassword) {
    ElMessage.info('没有需要修改的内容')
    return
  }

  saving.value = true
  try {
    const updated = await updateProfile({
      email,
      oldPassword: newPassword ? form.oldPassword : null,
      newPassword: newPassword || null
    })
    profile.value = updated || { ...profile.value, email: email || null }
    ElMessage.success(newPassword ? '资料与密码已更新' : '资料已更新')
    cancelEdit()
  } catch {
    // 错误提示由 http 拦截器统一处理
  } finally {
    saving.value = false
  }
}

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-title">
      <h2>个人中心</h2>
      <el-button text @click="router.push({ name: 'repos' })">
        <el-icon><ArrowLeft /></el-icon>
        <span class="btn-text">返回我的仓库</span>
      </el-button>
    </div>

    <div v-loading="loading" class="profile-wrap">
      <transition name="swap" mode="out-in">
        <div v-if="!editing" key="info" class="profile-view">
          <header class="card profile-hero">
            <div class="avatar-wrap">
              <button
                type="button"
                class="avatar"
                :class="{ uploading }"
                :disabled="uploading || loading"
                title="更换头像"
                @click="pickAvatar"
              >
                <img v-if="avatarUrl" :src="avatarUrl" alt="头像" />
                <span v-else>{{ initials }}</span>
                <span class="avatar-mask">
                  <el-icon v-if="uploading" class="is-loading"><Loading /></el-icon>
                  <el-icon v-else><Camera /></el-icon>
                </span>
              </button>
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif"
                @change="onAvatarSelected"
              />
            </div>

            <div class="hero-text">
              <div class="hero-name">{{ auth.username || '未登录' }}</div>
              <div class="hero-sub">
                <span>UID {{ auth.userId || '-' }}</span>
                <span class="dot-sep">·</span>
                <span>注册于 {{ formatDateTime(profile?.createTime) }}</span>
              </div>
              <div class="hero-tip">点击头像更换，支持 PNG / JPEG / WebP / GIF，最大 5MB</div>
            </div>

            <el-button text class="hero-action" @click="startEdit">
              <el-icon><EditPen /></el-icon>
              <span class="btn-text">修改资料</span>
            </el-button>
          </header>

          <div class="profile-grid">
            <section class="card info-card">
              <h3 class="card-title">基础信息</h3>
              <ul class="info-list">
                <li class="info-row">
                  <span class="info-icon"><el-icon><User /></el-icon></span>
                  <span class="info-label">用户名</span>
                  <span class="info-value">{{ auth.username || '—' }}</span>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><Postcard /></el-icon></span>
                  <span class="info-label">用户 ID</span>
                  <span class="info-value mono">{{ auth.userId || '—' }}</span>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><Message /></el-icon></span>
                  <span class="info-label">邮箱</span>
                  <span class="info-value">
                    <template v-if="profile?.email">{{ profile.email }}</template>
                    <template v-else>
                      <span class="empty-tag">未填写</span>
                      <button type="button" class="inline-link" @click="startEdit">去绑定</button>
                    </template>
                  </span>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><Calendar /></el-icon></span>
                  <span class="info-label">注册时间</span>
                  <span class="info-value">{{ formatDateTime(profile?.createTime) }}</span>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><RefreshRight /></el-icon></span>
                  <span class="info-label">最近更新</span>
                  <span class="info-value">{{ formatDateTime(profile?.updateTime) }}</span>
                </li>
              </ul>
            </section>

            <section class="card info-card">
              <h3 class="card-title">账户安全</h3>
              <ul class="info-list">
                <li class="info-row">
                  <span class="info-icon"><el-icon><Key /></el-icon></span>
                  <span class="info-label">登录密码</span>
                  <span class="info-value muted-value">••••••••</span>
                  <button type="button" class="row-link" @click="startEdit">修改</button>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><Lock /></el-icon></span>
                  <span class="info-label">登录令牌</span>
                  <span
                    class="info-value mono token-value"
                    :title="tokenVisible ? '已显示完整令牌' : '已隐藏，点击右侧按钮查看'"
                  >{{ shownToken }}</span>
                  <span class="row-actions">
                    <button
                      type="button"
                      class="icon-action"
                      :title="tokenVisible ? '隐藏令牌' : '显示令牌'"
                      @click="tokenVisible = !tokenVisible"
                    >
                      <el-icon><Hide v-if="tokenVisible" /><View v-else /></el-icon>
                    </button>
                    <button type="button" class="icon-action" title="复制令牌" @click="copyToken">
                      <el-icon><CopyDocument /></el-icon>
                    </button>
                  </span>
                </li>
                <li class="info-row">
                  <span class="info-icon"><el-icon><Monitor /></el-icon></span>
                  <span class="info-label">登录状态</span>
                  <span class="info-value status-value">
                    <span class="status-dot" />
                    当前设备
                  </span>
                </li>
              </ul>

              <div class="security-foot">
                <button type="button" class="logout-link" @click="logout">退出当前账号</button>
              </div>
            </section>
          </div>
        </div>

        <section v-else key="edit" class="card edit-card">
          <div class="edit-head">
            <div>
              <div class="form-title">修改资料</div>
              <div class="form-sub">邮箱可随时修改；密码留空表示保持不变，填写后需要用原密码验证。</div>
            </div>
            <el-button text @click="cancelEdit">
              <el-icon><ArrowLeft /></el-icon>
              <span class="btn-text">返回资料</span>
            </el-button>
          </div>

          <el-form label-position="top" @submit.prevent>
            <el-form-item label="邮箱">
              <el-input v-model="form.email" placeholder="例如 you@example.com" clearable maxlength="255" />
            </el-form-item>

            <div class="section-label">
              <span class="dot" />
              修改密码（可选）
            </div>

            <el-form-item label="原密码">
              <el-input
                v-model="form.oldPassword"
                type="password"
                show-password
                placeholder="修改密码时必填"
                autocomplete="current-password"
              />
            </el-form-item>

            <div class="password-grid">
              <el-form-item label="新密码">
                <el-input
                  v-model="form.newPassword"
                  type="password"
                  show-password
                  placeholder="至少 6 位"
                  autocomplete="new-password"
                />
              </el-form-item>
              <el-form-item label="确认新密码">
                <el-input
                  v-model="form.confirmPassword"
                  type="password"
                  show-password
                  placeholder="再输入一次"
                  autocomplete="new-password"
                />
              </el-form-item>
            </div>

            <div class="form-actions">
              <el-button :disabled="saving" @click="cancelEdit">取消</el-button>
              <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
            </div>
          </el-form>

          <div class="muted foot-note">修改密码后，已签发的登录令牌在过期前仍然有效。</div>
        </section>
      </transition>
    </div>
  </div>
</template>

<style scoped>
.profile-wrap {
  min-height: 420px;
}

.profile-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card {
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--board-bg);
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 22px 24px;
}

.avatar-wrap {
  position: relative;
  flex: 0 0 auto;
}

.avatar {
  position: relative;
  flex: 0 0 auto;
  width: 76px;
  height: 76px;
  padding: 0;
  border: 0;
  border-radius: 22px;
  overflow: hidden;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: inherit;
  font-size: 28px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.95), rgba(34, 211, 238, 0.6));
  box-shadow: 0 10px 26px rgba(79, 70, 229, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.28);
  transition: transform var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.avatar:hover:not(:disabled),
.avatar:focus-visible {
  transform: translateY(-2px);
  box-shadow: 0 14px 30px rgba(79, 70, 229, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.avatar:disabled {
  cursor: default;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #fff;
  background: rgba(2, 6, 23, 0.55);
  backdrop-filter: blur(2px);
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.avatar:hover .avatar-mask,
.avatar:focus-visible .avatar-mask,
.avatar.uploading .avatar-mask {
  opacity: 1;
}

.file-input {
  display: none;
}

.hero-text {
  flex: 1;
  min-width: 0;
}

.hero-name {
  font-size: 22px;
  font-weight: 700;
  background: var(--grad-text);
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
}

.hero-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  color: var(--text-3);
  font-size: 13px;
}

.dot-sep {
  color: var(--text-3);
}

.hero-tip {
  margin-top: 8px;
  color: var(--text-3);
  font-size: 12.5px;
}

/* 幽灵按钮：透明底 + 细描边，只作为次级操作 */
.hero-action.el-button.is-text {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--line-strong);
  background: transparent;
  color: var(--text-1);
}

.hero-action.el-button.is-text:hover {
  border-color: var(--accent-1);
  background: var(--hover-1);
  color: var(--accent-1);
}

.btn-text {
  margin-left: 4px;
}

/* ---------- 双栏信息区 ---------- */
.profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.info-card {
  padding: 18px 20px 16px;
}

.card-title {
  margin: 0 0 6px;
  color: var(--text-3);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.info-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 行之间不画线，靠 16px 上下的留白区分 */
.info-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  margin: 0 -8px;
  padding: 10px 8px;
  border-radius: 9px;
  transition: background-color 120ms var(--ease);
}

.info-row:hover {
  background: var(--hover-1);
}

.info-icon {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--hover-1);
  color: var(--text-2);
  font-size: 14px;
}

.info-label {
  flex: 0 0 72px;
  color: var(--text-2);
  font-size: 13px;
}

.info-value {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--text-0);
  font-size: 13.5px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted-value {
  color: var(--text-3);
  letter-spacing: 1px;
}

.token-value {
  color: var(--text-1);
  font-size: 12px;
}

.status-value {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-ok);
  box-shadow: 0 0 6px var(--accent-ok);
}

.empty-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--hover-2);
  color: var(--text-3);
  font-size: 11.5px;
}

.inline-link,
.row-link {
  margin-left: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: color 120ms var(--ease);
}

.inline-link:hover,
.row-link:hover {
  color: var(--link-hover);
}

.row-actions {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 2px;
}

.icon-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-3);
  font-size: 14px;
  cursor: pointer;
  transition: background-color 120ms var(--ease), color 120ms var(--ease);
}

.icon-action:hover {
  background: var(--hover-2);
  color: var(--text-0);
}

.security-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding-top: 6px;
}

/* 退出入口降到页面边缘：默认灰字，hover 才变红 */
.logout-link {
  padding: 4px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-3);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: color 120ms var(--ease), background-color 120ms var(--ease);
}

.logout-link:hover {
  background: oklch(0.58 0.19 25 / 0.08);
  color: var(--accent-danger);
}

/* ---------- 编辑态 ---------- */
.edit-card {
  padding: 22px 24px;
}

.edit-card .foot-note {
  margin-top: 18px;
}

.edit-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.form-title {
  font-size: 16px;
  font-weight: 600;
}

.form-sub {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
}

.section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 10px;
  font-size: 12px;
  letter-spacing: 0.4px;
  color: var(--text-2);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-2);
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.8);
}

.password-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.foot-note {
  font-size: 12px;
}

/* 资料与表单互换：淡入 + 轻微位移，避免整块生硬跳变 */
.swap-enter-active,
.swap-leave-active {
  transition: opacity 180ms var(--ease), transform 180ms var(--ease);
}

.swap-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.swap-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 900px) {
  .profile-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 560px) {
  .profile-hero {
    flex-wrap: wrap;
    padding: 18px;
  }

  .password-grid {
    grid-template-columns: 1fr;
  }

  .avatar {
    width: 64px;
    height: 64px;
    border-radius: 19px;
    font-size: 24px;
  }

  .hero-action.el-button.is-text {
    margin-left: 82px;
  }

  .info-card {
    padding: 16px;
  }

  .info-label {
    flex-basis: 64px;
  }

  .edit-card {
    padding: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .swap-enter-active,
  .swap-leave-active {
    transition: opacity 120ms linear;
  }

  .swap-enter-from,
  .swap-leave-to {
    transform: none;
  }
}
</style>
