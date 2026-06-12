FROM node:20-alpine AS builder
WORKDIR /app
# (#632) Grafana trace 딥링크 URL을 빌드타임 주입. VITE_*는 build 시 정적으로 박히므로 build-arg로 받는다.
# CI(Jenkins kaniko)가 --build-arg VITE_GRAFANA_TRACE_URL=... 로 전달. 비우면 프론트에서 딥링크 버튼 숨김(graceful).
ARG VITE_GRAFANA_TRACE_URL=
ENV VITE_GRAFANA_TRACE_URL=${VITE_GRAFANA_TRACE_URL}
COPY package.json ./
RUN npm install -g pnpm && pnpm install
COPY . .
RUN pnpm build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
