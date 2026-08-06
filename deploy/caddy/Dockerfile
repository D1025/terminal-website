FROM node:22-alpine AS frontend-build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY index.html postcss.config.cjs tailwind.config.js vite.config.js ./
COPY public public
COPY src src
RUN npm run build

FROM caddy:2-alpine
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=frontend-build /workspace/dist /srv

