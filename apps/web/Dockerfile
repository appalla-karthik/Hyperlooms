FROM node:26-alpine AS dependencies
RUN corepack enable
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json ./apps/web/package.json
RUN pnpm install --frozen-lockfile

FROM node:26-alpine AS build
RUN corepack enable
WORKDIR /workspace
COPY --from=dependencies /workspace/node_modules ./node_modules
COPY --from=dependencies /workspace/apps/web/node_modules ./apps/web/node_modules
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web ./apps/web
RUN pnpm --dir apps/web build

FROM node:26-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app
RUN addgroup --system --gid 10001 hyperlooms && adduser --system --uid 10001 --ingroup hyperlooms hyperlooms
COPY --from=build --chown=hyperlooms:hyperlooms /workspace/apps/web/.next/standalone ./
COPY --from=build --chown=hyperlooms:hyperlooms /workspace/apps/web/.next/static ./apps/web/.next/static
USER hyperlooms
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
CMD ["node", "apps/web/server.js"]
