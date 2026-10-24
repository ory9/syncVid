# Root-level Dockerfile so Railway can build the backend when the repo root is the project root.
# (If you set the Railway service's Root Directory to "backend", backend/Dockerfile is used instead.)
FROM node:22-slim AS build
WORKDIR /app
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/tsconfig.json ./
COPY backend/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-slim
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY backend/package.json ./
USER node
EXPOSE 8080
CMD ["node", "dist/src/index.js"]
