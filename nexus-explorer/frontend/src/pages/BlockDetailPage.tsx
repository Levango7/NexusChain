import React, { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { BlockInfo } from "../types";
import { DetailPageLayout } from "../components/ui";

/**
 * BlockDetailPage — 区块详情页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens
 *   - 内联 &larr; / &rarr; 字符替换为 lucide-react <ChevronLeft / ChevronRight />
 *   - Loading 文案替换为 <Loading /> 组件（经 DetailPageLayout 统一）
 *   - 间距 / 圆角 / 字体统一 token
 *   - P1: 复用 DetailPageLayout 提取的 header + loading + error 骨架
 */
const BlockDetailPage: React.FC = () => {
  const { t } = useTranslation();
  const { height } = useParams<{ height: string }>();
  const navigate = useNavigate();
  const [block, setBlock] = useState<BlockInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // height 为 undefined 时 Number(undefined) === NaN，会请求 /api/blocks/NaN
    if (!height) return;
    (async () => {
      try {
        const data = await api.getBlock(Number(height));
        setBlock(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : t("block.notFound"));
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [height]);

  if (loading || error || !block) {
    return (
      <DetailPageLayout
        loading={loading}
        loadingLabel={t("block.loadingBlock", { height })}
        error={error}
        title={
          block ? (
            <>
              {t("block.title")}{" "}
              <span className="text-accent font-mono">#{block.height}</span>
            </>
          ) : undefined
        }
        backLabel={t("block.back")}
      />
    );
  }

  const rows: [string, React.ReactNode][] = [
    [
      t("block.height"),
      <span className="font-mono text-accent">{block.height}</span>,
    ],
    [t("block.hash"), <code className="break-all text-xs text-fg">{block.hash}</code>],
    [
      t("block.parentHash"),
      <code className="break-all text-xs text-muted">{block.parentHash}</code>,
    ],
    [t("block.proposer"), <code className="text-xs text-fg">{block.proposer}</code>],
    [t("block.timestamp"), new Date(block.timestamp * 1000).toLocaleString()],
    [t("block.transactions"), `${block.txCount}`],
    [t("block.size"), `${block.size} ${t("block.bytes")}`],
    [t("block.difficulty"), `${block.difficulty}`],
  ];

  return (
    <DetailPageLayout
      title={
        <>
          {t("block.title")}{" "}
          <span className="text-accent font-mono">#{block.height}</span>
        </>
      }
      backLabel={t("block.back")}
    >
      <div className="bg-surface border border-border rounded-lg divide-y divide-border-soft">
        {rows.map(([label, value]) => (
          <div
            key={label}
            className="px-5 py-3 grid grid-cols-4 gap-2 text-sm"
          >
            <span className="text-muted">{label}</span>
            <span className="col-span-3 text-fg">{value}</span>
          </div>
        ))}
      </div>
      <div className="mt-4 flex gap-2">
        <button
          onClick={() => navigate(`/block/${block.height - 1}`)}
          aria-label={t("block.prevBlock")}
          disabled={block.height <= 0}
          className="inline-flex items-center gap-1 text-xs px-3 py-1.5 bg-surface-2 border border-border rounded-sm text-fg-2 hover:border-accent hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:border-border disabled:hover:text-fg-2"
        >
          <ChevronLeft size={14} />
          {t("block.prev")}
        </button>
        <button
          onClick={() => navigate(`/block/${block.height + 1}`)}
          aria-label={t("block.nextBlock")}
          className="inline-flex items-center gap-1 text-xs px-3 py-1.5 bg-surface-2 border border-border rounded-sm text-fg-2 hover:border-accent hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
        >
          {t("block.next")}
          <ChevronRight size={14} />
        </button>
      </div>
    </DetailPageLayout>
  );
};

export default BlockDetailPage;
