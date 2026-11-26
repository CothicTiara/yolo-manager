# 前端镜像：多阶段构建（宿主机无需 Node.js），产物由 nginx 提供并反向代理到管理后端
FROM node:20-alpine AS build
WORKDIR /app
ENV npm_config_fetch_retries=5

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
