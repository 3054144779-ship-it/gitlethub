import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import router from '../router'

/**
 * 统一 axios 实例。后端所有接口都返回 {code, message, data}：
 * code === 0 视为成功，直接把 data 交给调用方；否则抛出 Error(message)。
 * 这样页面里只需要 try/catch 或 .catch()，不用每次判断 code。
 */
const http = axios.create({ timeout: 20000 })

// 同一轮操作里并发失败的请求只提示一次，避免顶部连弹好几条
const TOAST_GAP = 1200
let lastToastAt = 0

function toastError(message) {
  const now = Date.now()
  if (now - lastToastAt < TOAST_GAP) return
  lastToastAt = now
  ElMessage({ message, type: 'error', duration: 2600, grouping: true, offset: 24 })
}

// 把 axios / 网关的技术报错翻译成用户看得懂的话
function friendlyError(error) {
  if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
    return '请求超时，请稍后重试'
  }
  const status = error.response?.status
  if (!status) return '网络连接异常，请检查网络后重试'
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '没有权限执行此操作'
  if (status === 404) return '请求的资源不存在'
  if (status === 429) return '请求太频繁，请稍后再试'
  if (status >= 500) return '服务器开小差了，请稍后重试'
  const message = error.response?.data?.message
  return typeof message === 'string' && message ? message : '请求失败，请稍后重试'
}

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code !== 'undefined') {
      if (body.code === 0) {
        return body.data
      }
      const message = body.message || '请求失败，请稍后重试'
      // silent 用于批量探测/聚合请求：失败由调用方自行降级，避免同时弹出多条提示
      if (!response.config?.silent) toastError(message)
      return Promise.reject(new Error(message))
    }
    return body
  },
  (error) => {
    // 主动取消的请求不提示
    if (error.code === 'ERR_CANCELED') return Promise.reject(error)

    if (error.response?.status === 401) {
      useAuthStore().logout()
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login' })
      }
    }

    // 调用方读取 error.message 时也只拿到面向用户的文案
    error.message = friendlyError(error)
    if (!error.config?.silent) toastError(error.message)
    return Promise.reject(error)
  }
)

export default http
