# ---- 构建阶段 ----
FROM node:22-alpine AS build

WORKDIR /app

# 先只拷依赖清单，依赖没变时可以复用缓存层
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:alpine

# nginx.conf 是模板：官方镜像启动时会用 envsubst 渲染成
# /etc/nginx/conf.d/default.conf，只替换 ${GATEWAY_UPSTREAM}（见 compose 里的 NGINX_ENVSUBST_FILTER）
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
