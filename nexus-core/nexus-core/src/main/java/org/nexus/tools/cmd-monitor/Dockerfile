# 运维监控工具镜像：node:10-alpine（EOL）→ node:24-alpine；
# npm ci（lockfile 确定性安装，剔除未引用依赖）+ 非 root（node）运行。
FROM node:24-alpine

WORKDIR /usr/src/app

COPY package*.json ./

RUN npm ci --omit=dev && npm cache clean --force

COPY *.js ./

USER node

CMD ["node", "schedule-monitor.js"]
