# Self-hosting: docker build -t wbrowser-backend . && docker run -d -p 4000:4000 -p 127.0.0.1:4001:4001 \
#   -v wbdata:/app/data -e NODE_ENV=production -e ADMIN_JWT_SECRET=<48+ random bytes> -e ADMIN_BIND=0.0.0.0 wbrowser-backend
FROM node:24-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
ENV DB_PATH=/app/data/wbrowser.db
VOLUME /app/data
EXPOSE 4000 4001
USER node
CMD ["node", "src/server.js"]
