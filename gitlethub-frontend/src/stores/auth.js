import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const TOKEN_KEY = 'gitlethub.token'
const USER_ID_KEY = 'gitlethub.userId'
const USERNAME_KEY = 'gitlethub.username'
const AVATAR_KEY = 'gitlethub.avatar'

/**
 * 登录态。token 由 user-service 登录接口下发；网关尚未启用，
 * 各服务目前不校验 token，但请求头按最终形态先带上。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const userId = ref(localStorage.getItem(USER_ID_KEY) || '')
  const username = ref(localStorage.getItem(USERNAME_KEY) || '')
  const avatar = ref(localStorage.getItem(AVATAR_KEY) || '')

  const isLoggedIn = computed(() => Boolean(token.value))

  function setLogin(loginVO) {
    token.value = loginVO.token || ''
    userId.value = loginVO.userId ?? ''
    username.value = loginVO.username || ''
    localStorage.setItem(TOKEN_KEY, token.value)
    localStorage.setItem(USER_ID_KEY, String(userId.value))
    localStorage.setItem(USERNAME_KEY, username.value)
  }

  function setAvatar(url) {
    avatar.value = url || ''
    if (avatar.value) {
      localStorage.setItem(AVATAR_KEY, avatar.value)
    } else {
      localStorage.removeItem(AVATAR_KEY)
    }
  }

  function logout() {
    token.value = ''
    userId.value = ''
    username.value = ''
    avatar.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_ID_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(AVATAR_KEY)
  }

  return { token, userId, username, avatar, isLoggedIn, setLogin, setAvatar, logout }
})
