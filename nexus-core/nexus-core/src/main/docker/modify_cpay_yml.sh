#!/usr/bin/env bash
# ============================================================================
# @deprecated since v2.1.0 — 旧版 cpay.yml 修改脚本，保留仅作历史参考。
# P2 清理项：标注 deprecated，后续迭代删除。
# ============================================================================
sed -i -E "s+(.*?)entrypoint:.*+\1entrypoint: /usr/bin/env bash /entry_point.sh -d nexus_pgsql_v0.0.3:5432 -c '/usr/bin/env bash /run_nexus_core.sh'+" nexus.yml
