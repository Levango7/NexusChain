#!/usr/bin/env python3
"""打包一致性检查：jar 排除了配置 → 容器必须另行提供配置。

背景（2026-09-21 实证）
    nexus-core 的 `jar` 任务显式排除 `application.properties` 与
    `application-local.properties`，用于修复「core 作为**库**被
    wallet/signing 经 nexus-sdk 传递依赖时，其配置进入服务侧 Spring Boot
    config data 加载链，用 server.port=19585 覆盖对方端口」的问题。

    但该处注释同时断言「core 独立运行形态（Dockerfile 源码全量构建）
    不受影响」——**与事实不符**：Dockerfile 执行的正是
    `:nexus-core:nexus-core:jar`，容器用的就是那个不含配置的 jar。
    后果：容器内启动即失败
        PlaceholderResolutionException:
          Could not resolve placeholder 'transaction.day.count'
          at org.nexus.Start.main
    而 DAST 门禁（OWASP ZAP）因此**从未真正扫描过任何东西**。

    这类「A 处修复破坏了 B 处、且注释里写了一句错误的安心话」的缺陷，
    静态检查无法从单文件看出，必须做**跨文件一致性**校验 —— 本脚本即为此。

检查项
    若某模块的 Gradle `jar` 任务排除了 Spring Boot 配置文件，
    则该模块的容器运行路径必须满足：
      1. Dockerfile 把配置文件带进镜像
      2. 编排（docker-compose / k8s）显式指定配置位置
    任一缺失即失败。

用法
    python scripts/check-packaging-consistency.py

退出码
    0 = 一致
    1 = 存在不一致
"""
from __future__ import annotations

import io
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 被检查的模块：模块目录 -> (build.gradle, Dockerfile)
MODULES = [
    ('nexus-core', 'nexus-core/nexus-core/build.gradle', 'nexus-core/Dockerfile'),
]

COMPOSE_FILES = ['docker-compose.yml']

CONFIG_FILE_RE = re.compile(r"exclude\s+'?(application[\w.-]*\.(?:properties|yml|yaml))'?")


def read(path: str) -> str:
    full = os.path.join(REPO_ROOT, path)
    if not os.path.exists(full):
        return ''
    return io.open(full, encoding='utf-8', errors='ignore').read()


def main() -> int:
    failures = []
    checks = 0

    for name, gradle_rel, dockerfile_rel in MODULES:
        gradle = read(gradle_rel)
        if not gradle:
            continue

        excluded = sorted(set(CONFIG_FILE_RE.findall(gradle)))
        if not excluded:
            continue

        checks += 1
        print('[%s] jar 排除了配置文件：%s' % (name, ', '.join(excluded)))

        # 检查 1：Dockerfile 是否把配置带进镜像
        # 逐项核对：**每一个**被排除的具体文件名都必须在 Dockerfile 中出现。
        # 只检查「有没有 COPY 配置」是不够的 —— 实测遗漏 application.yml 时
        # 该弱检查仍会通过（yml 头部注释明确写明它提供被 ${...} 引用的默认值，
        # 缺失同样导致启动失败）。故此处按文件名逐项匹配。
        dockerfile = read(dockerfile_rel)
        if not dockerfile:
            failures.append('%s：jar 排除了配置，但找不到 %s' % (name, dockerfile_rel))
            continue

        # 只在**非注释行**中查找。
        # 教训（2026-09-21，被本脚本的反向测试抓出）：首版用
        # `f not in dockerfile` 直接全文匹配，结果 Dockerfile 里**注释中**
        # 提到 application.yml 就让检查误判为"已带入" —— 移除实际 cp 命令后
        # 守卫依然通过，等于不设防。
        code_lines = [
            l for l in dockerfile.splitlines()
            if l.strip() and not l.lstrip().startswith('#')
        ]
        code = '\n'.join(code_lines)

        concrete = [f for f in excluded if '*' not in f]
        missing = [f for f in concrete if f not in code]
        if missing:
            failures.append(
                '%s：jar 排除了 %s，但 %s 未把这些文件带进镜像 —— '
                '容器将因缺少配置而启动失败'
                % (name, ', '.join(missing), dockerfile_rel))
        else:
            print('  ✓ Dockerfile 已带入全部 %d 个具体配置文件' % len(concrete))

        # 通配模式（application-*.yml 等）单独提示：需人工确认覆盖范围
        wildcards = [f for f in excluded if '*' in f]
        if wildcards:
            print('  · 通配排除项需人工确认是否已覆盖：%s' % ', '.join(wildcards))

        # 检查 2：编排是否显式指定配置位置
        found_env = None
        for compose_rel in COMPOSE_FILES:
            compose = read(compose_rel)
            if not compose:
                continue
            if re.search(r'SPRING_CONFIG_ADDITIONAL_LOCATION|SPRING_CONFIG_LOCATION',
                         compose):
                found_env = compose_rel
                break
        if found_env:
            print('  ✓ %s 显式指定了配置位置' % found_env)
        else:
            failures.append(
                '%s：jar 排除了配置，但 %s 未设置 '
                'SPRING_CONFIG_ADDITIONAL_LOCATION —— 容器无法加载被排除的配置'
                % (name, '/'.join(COMPOSE_FILES)))

    print()
    if checks == 0:
        print('✅ 无需检查（没有模块从 jar 中排除配置）')
        return 0

    if failures:
        print('❌ 打包一致性检查失败：')
        for f in failures:
            print('   - ' + f)
        print()
        print('说明：jar 排除配置是为了避免 core 作为库时污染下游服务的配置链；')
        print('      但容器是独立运行形态，必须另行提供配置，否则启动即失败。')
        return 1

    print('✅ 打包一致性检查通过（共 %d 个模块）' % checks)
    return 0


if __name__ == '__main__':
    sys.exit(main())
