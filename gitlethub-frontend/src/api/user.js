import http from './http'

/** 注册：{username, password, email} */
export function register(payload) {
  return http.post('/user/registration', payload)
}

/** 登录：返回 {token, userId, username} */
export function login(payload) {
  return http.post('/user/login', payload)
}

/** 用户详情（不含密码） */
export function getUser(userId) {
  return http.get(`/user/${userId}`)
}

/** 修改当前登录用户资料：{email, avatar, oldPassword, newPassword} */
export function updateProfile(payload) {
  return http.put('/user/profile', payload)
}

/** 获取 OSS 直传预签名地址：返回 { uploadUrl, accessUrl, contentType } */
export function getAvatarUploadUrl(fileName, contentType) {
  return http.get('/api/oss/presigned-url', { params: { fileName, contentType } })
}

/** 按用户名分页搜索用户：{username, pageNum, pageSize}；config 可传 { silent: true } */
export function searchUsers(params, config = {}) {
  return http.get('/user/username/list', { params, ...config })
}
