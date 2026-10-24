FROM node:22-alpine AS build
WORKDIR /workspace
ARG VITE_ENABLE_VOICE_ASSISTANT=true
ENV VITE_ENABLE_VOICE_ASSISTANT=${VITE_ENABLE_VOICE_ASSISTANT}
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY . .
RUN pnpm install --frozen-lockfile
RUN pnpm run build

FROM nginx:1.27-alpine
COPY deploy/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80 443
