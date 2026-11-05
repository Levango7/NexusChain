# 运维监控工具镜像：node:10-alpine（EOL）→ node:24-alpine；
# npm ci（lockfile 确定性安装，剔除未引用依赖）+ 非 root（node）运行。
FROM node:24-alpine

WORKDIR /usr/src/app

COPY package*.json ./

RUN npm ci --omit=dev && npm cache clean --force

# 2026-11-05（对齐 nexus-explorer 2026-10-19 的处置，跟踪 issue #495）：
# 删除基线镜像自带的 npm/npx/corepack。node:24-alpine 的 npm（11.19.1）
# 自带一份打包的 node_modules，其中 brace-expansion 5.0.9 / undici 6.28.0
# 携带 6 个有修复版的 HIGH CVE（CVE-2026-102276 / -102278 / -109383 /
# -109869 / -19534 / -87018），路径在 /usr/local/lib/node_modules/npm/**。
# 本镜像只执行 `node schedule-monitor.js`，包管理器运行期永不被调用——
# 属基线镜像层的死代码，按「可消除项不豁免」原则整树删除，同时缩小运行时
# 文件系统与供应链暴露面；不写入 .trivyignore（上游已有修复版，见 #495）。
RUN rm -rf /usr/local/lib/node_modules/npm \
           /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx \
           /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg

COPY *.js ./

USER node

CMD ["node", "schedule-monitor.js"]
