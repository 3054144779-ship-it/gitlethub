import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前后端分离：开发期用 Vite 代理直连两个后端服务，页面与后端同源 → 无 CORS 问题。
// 网关（gateway-service）目前不可用，所以这里不经网关，直接转发到各服务。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    build: {
      commonjsOptions: {
        // @vueuse/* 是纯 ESM（package.json 中 type: module，只是文件名用了 .js），
        // 但 commonjs 插件仍会逐文件解析它；在 Node 24 + rollup 4.63 上这层嵌套解析会
        // "Maximum call stack size exceeded"。直接排除，让 rollup 按 ESM 原生处理。
        exclude: [/node_modules[\\/]@vueuse[\\/]/]
      }
    },
    server: {
      port: 5173,
      proxy: {
        // remote-gitlet：仓库 / 分支 / 提交 / PR / 协作者
        '/api': {
          target: env.VITE_GATEWAY_TARGET || 'http://47.111.125.246:8080',
          changeOrigin: true
          // 不rewrite，保留/api
        },
        // user-service：注册 / 登录 / 用户信息
        '/user': {
          target: env.VITE_GATEWAY_TARGET || 'http://47.111.125.246:8080',
          changeOrigin: true,
          // rewrite: (path) => '/api' + path
        }
      }
    }
  }
})
