import React, { useState, useEffect } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { ArrowLeft, ChevronLeft, ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { BlockInfo } from "../types";
import { Loading } from "../components/ui";

/**
 * BlockDetailPage — 区块详情页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens
 *   - 内联 &larr; / &rarr; 字符替换为 lucide-react <ArrowLeft / ChevronLeft / ChevronRight />
 *   - Loading 文案替换为 <Loading /> 组件
 *   - 间距 / 圆角 / 字体统一 token
 */
const BlockDetailPage: React.FC = () => {
  const { t } = useTranslation();
  const { height } = useParams<{ height: string }>();
  const navigate = useNavigate();
  const [block, setBlock] = useState<BlockInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
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
  }, [height, t]);

  if (loading)
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Loading label={t("block.loadingBlock", { height })} />
      </div>
    );
  if (error)
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center text-danger">
        {error}
      </div>
    );
  if (!block) return null;

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
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link
            to="/"
            className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <ArrowLeft size={14} />
            {t("block.back")}
          </Link>
          <h1 className="text-sm font-semibold text-fg-2">
            {t("block.title")} <span className="text-accent font-mono">#{block.height}</span>
          </h1>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-6">
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
            className="inline-flex items-center gap-1 text-xs px-3 py-1.5 bg-surface-2 border border-border rounded-sm text-fg-2 hover:border-accent hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
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
      </main>
    </div>
  );
};

export default BlockDetailPage;
