# dep-graph frontend
# ==================
# Its own build context: this module needs nothing from the Maven reactor, which is why
# the compose service sets `context: ./dep-graph-frontend`.
#
#   docker compose up -d --build frontend

FROM node:22-alpine AS builder

WORKDIR /build

# package.json first, so a source-only change does not reinstall node_modules.
COPY package.json package-lock.json* ./
RUN npm install

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
