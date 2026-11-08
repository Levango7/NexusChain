import React from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Compass } from "lucide-react";
import { PageHeader, BackLink } from "../components/layout/PageHeader";
import { Button } from "../components/ui";

/**
 * NotFoundPage — 未知路径兜底页（2026-11-07 生产就绪度审计修复，发现 B10）。
 *
 * <h3>修的是什么</h3>
 * <p>审计实测：`App.tsx` 列了 11 条 `<Route>` 却<b>没有 `path="*"` 兜底</b>，
 * 于是任意未匹配路径下 React Router 渲染 `null` ⇒ `<div id="root">` 保持空
 * ⇒ <b>纯白屏</b>（实测 `bodyHtmlLen=80`、`rootChildCount=0`、`bodyTextLen=0`）。</p>
 * <p>更糟的是：<b>本仓没有承载导航的布局路由</b> —— 首页自己渲染那 6 个导航链接，
 * 其它页面渲染「返回」链接。所以未匹配时用户既看不到导航也看不到返回，
 * <b>被完全困在白屏里，只能手改地址栏</b>。</p>
 *
 * <h3>为什么不用 `EmptyState` 而是自己写</h3>
 * <p>这是一张独立页面（要带 shell + 返回入口），不是行内空态；行内空态与错误态
 * 见 `components/ui/ErrorState.tsx`。</p>
 */
const NotFoundPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();

  return (
    <div className="min-h-screen bg-bg text-fg">
      <PageHeader maxWidth="max-w-6xl" innerClassName="justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <BackLink>{t("common.backHome")}</BackLink>
          <span className="text-xs text-muted font-mono truncate hidden sm:inline">
            {t("common.notFoundTitle")}
          </span>
        </div>
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-16">
        <div className="max-w-xl mx-auto text-center space-y-4">
          <div className="flex justify-center">
            <Compass size={40} aria-hidden="true" className="text-muted" />
          </div>

          <h1 className="text-xl font-semibold">{t("common.notFoundTitle")}</h1>
          <p className="text-sm text-muted">{t("common.notFoundDesc")}</p>

          {/* 把访问到的路径回显出来 —— 用户据此能一眼看出是链接过期还是手输错 */}
          <p className="text-xs text-muted font-mono break-all">{location.pathname}</p>

          <div className="flex items-center justify-center gap-3 pt-2">
            <Button variant="primary" size="md" onClick={() => navigate("/")}>
              {t("common.backHome")}
            </Button>
            <Button variant="ghost" size="md" onClick={() => navigate(-1)}>
              {t("common.back")}
            </Button>
          </div>
        </div>
      </main>
    </div>
  );
};

export default NotFoundPage;
