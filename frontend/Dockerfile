# syntax=docker/dockerfile:1
# =====================================================================
# Finesse frontend 이미지 (초안 — DevOps 작성, frontend 담당 확인 필요)
#
# 빌드:  docker build -t finesse-frontend ./frontend
# 실행:  docker run -p 8080:80 -e BACKEND_UPSTREAM=http://<backend 주소>:8081 finesse-frontend
#
# 구조: Vite로 정적 파일 빌드 → Nginx가 서빙.
#       /api/** 요청은 Nginx가 BACKEND_UPSTREAM(backend)으로 넘긴다 (src/api/client.ts 주석의 "배포 후에는 Nginx" 구조).
# =====================================================================

# ---- 1단계: 빌드 (package.json engines: node ^22.12) ----
# alpine(musl) 대신 slim(glibc): tailwind/rollup/lightningcss 등 네이티브 바이너리 호환 문제를 피하기 위함
FROM node:22-slim AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- 2단계: 실행 (Nginx로 정적 파일 서빙) ----
FROM nginx:1.27-alpine

# 공식 nginx 이미지는 /etc/nginx/templates/*.template 의 ${환경변수}를 시작 시 치환해
# /etc/nginx/conf.d/ 에 넣어준다 → backend 주소를 이미지 재빌드 없이 실행 시 바꿀 수 있음
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# 기본값: docker compose 등에서 backend 서비스 이름이 "backend"일 때
ENV BACKEND_UPSTREAM=http://backend:8081

EXPOSE 80
