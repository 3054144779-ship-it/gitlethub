<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Key, Lock, Message, User } from '@element-plus/icons-vue'
import { login, register } from '../api/user'
import { useAuthStore } from '../stores/auth'
import AuthLayout from '../components/AuthLayout.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// /login 与 /register 共用同一个组件实例：切换表单时主视觉保持原状，不会退回第一屏
const isRegister = computed(() => route.name === 'register')

const layoutRef = ref()

// 先切到目标模式再展开，避免右侧先闪出另一个表单
async function handleOpen(mode) {
  const target = mode === 'register' ? 'register' : 'login'
  if (route.name !== target) {
    await router.push({ name: target, query: route.query })
  }
  layoutRef.value?.open()
}

const loginFormRef = ref()
const loginLoading = ref(false)
const loginForm = reactive({ username: '', password: '' })

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerFormRef = ref()
const registerLoading = ref(false)
const registerForm = reactive({ username: '', password: '', confirm: '', email: '' })

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3-32', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再输一次密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) =>
        value === registerForm.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

function clearLoginValidate(prop) {
  loginFormRef.value?.clearValidate(prop)
}

function clearRegisterValidate(prop) {
  registerFormRef.value?.clearValidate(prop)
}

async function submitLogin() {
  if (loginLoading.value) return
  // 校验只在提交时触发；输入框配了 validate-event=false，失焦不会提前标红
  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) return

  loginLoading.value = true
  try {
    const data = await login({ ...loginForm, rememberMe: 'false' })
    auth.setLogin(data)
    ElMessage.success('登录成功')
    router.push(route.query.redirect || { name: 'repos' })
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    loginLoading.value = false
  }
}

async function submitRegister() {
  if (registerLoading.value) return
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) return

  registerLoading.value = true
  try {
    await register({
      username: registerForm.username,
      password: registerForm.password,
      email: registerForm.email
    })
    ElMessage.success('注册成功，请登录')
    router.push({ name: 'login', query: route.query })
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    registerLoading.value = false
  }
}
</script>

<template>
  <AuthLayout
    ref="layoutRef"
    :swap-key="isRegister ? 'register' : 'login'"
    @open="handleOpen"
  >
    <header class="auth-head">
      <h2 class="auth-title">{{ isRegister ? '注册 GitletHub' : '登录 GitletHub' }}</h2>
      <p class="auth-subtitle">
        {{ isRegister ? '创建账号，开始管理你的代码仓库' : '欢迎回来，继续你的代码协作' }}
      </p>
    </header>

    <el-form
      v-if="!isRegister"
      ref="loginFormRef"
      class="auth-form"
      :model="loginForm"
      :rules="loginRules"
      @submit.prevent="submitLogin"
    >
      <el-form-item prop="username">
        <el-input
          v-model="loginForm.username"
          :prefix-icon="User"
          size="large"
          placeholder="用户名"
          autocomplete="username"
          :validate-event="false"
          @input="clearLoginValidate('username')"
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="loginForm.password"
          :prefix-icon="Lock"
          type="password"
          size="large"
          placeholder="密码"
          show-password
          autocomplete="current-password"
          :validate-event="false"
          @input="clearLoginValidate('password')"
          @keyup.enter="submitLogin"
        />
      </el-form-item>
      <el-button type="primary" class="auth-submit" :loading="loginLoading" @click="submitLogin">
        登录
      </el-button>
    </el-form>

    <el-form
      v-else
      ref="registerFormRef"
      class="auth-form"
      :model="registerForm"
      :rules="registerRules"
      @submit.prevent="submitRegister"
    >
      <el-form-item prop="username">
        <el-input
          v-model="registerForm.username"
          :prefix-icon="User"
          size="large"
          placeholder="用户名（3-32 位）"
          autocomplete="username"
          :validate-event="false"
          @input="clearRegisterValidate('username')"
        />
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="registerForm.password"
          :prefix-icon="Lock"
          type="password"
          size="large"
          placeholder="密码（至少 6 位）"
          show-password
          autocomplete="new-password"
          :validate-event="false"
          @input="clearRegisterValidate('password')"
        />
      </el-form-item>
      <el-form-item prop="confirm">
        <el-input
          v-model="registerForm.confirm"
          :prefix-icon="Key"
          type="password"
          size="large"
          placeholder="再次输入密码"
          show-password
          autocomplete="new-password"
          :validate-event="false"
          @input="clearRegisterValidate('confirm')"
        />
      </el-form-item>
      <el-form-item prop="email">
        <el-input
          v-model="registerForm.email"
          :prefix-icon="Message"
          size="large"
          placeholder="邮箱（选填）"
          autocomplete="email"
          :validate-event="false"
          @input="clearRegisterValidate('email')"
          @keyup.enter="submitRegister"
        />
      </el-form-item>
      <el-button type="primary" class="auth-submit" :loading="registerLoading" @click="submitRegister">
        注册
      </el-button>
    </el-form>

    <div class="auth-footer">
      <span>{{ isRegister ? '已有账号？' : '还没有账号？' }}</span>
      <router-link
        class="auth-link"
        :to="{ name: isRegister ? 'login' : 'register', query: route.query }"
      >
        {{ isRegister ? '返回登录' : '立即注册' }}
      </router-link>
    </div>
  </AuthLayout>
</template>
