import React, { useState, useEffect, useCallback } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ArrowLeft, Eye, EyeOff, CheckCircle2, AlertCircle, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/useAuth";
import { Button, Card } from "../components/ui";

/**
 * Settings — 用户凭证配置页面（P2-D3 UI 认证闭环）。
 *
 * 背景：
 *   - 旧版本将 HMAC secret 通过 VITE_NEXUS_API_SECRET 烧进 JS bundle，存在泄露风险。
 *   - AuthContext.setCredentials 此前无 UI 调用点，导致凭证无法在运行时配置。
 *
 * 修复：
 *   - 提供运行时输入 API Key / API Secret 的 UI 入口。
 *   - 凭证持久化到 localStorage（Secret 经 obfuscation 编码）。
 *   - 移除构建期 env 注入路径，secret 不再出现在 bundle 中。
 *
 * 设计契约：
 *   - 颜色全部走 design tokens（bg / surface / fg / muted / accent / success / danger）
 *   - 间距统一 max-w-4xl + px-4 + py-* 4 倍数
 *   - 圆角统一 rounded-md / rounded-lg
 *   - 图标统一 lucide-react
 */
const Settings: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { apiKey, apiSecret, isAuthenticated, setCredentials, clearCredentials } = useAuth();

  const [keyInput, setKeyInput] = useState<string>("");
  const [secretInput, setSecretInput] = useState<string>("");
  const [showSecret, setShowSecret] = useState<boolean>(false);
  const [saved, setSaved] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // 初始化输入框：显示当前已存储的凭证（apiKey 明文，secret 用占位符）
  useEffect(() => {
    setKeyInput(apiKey);
    // 已认证时 secret 用占位符，避免明文显示；用户重新输入才会更新
    setSecretInput(apiSecret ? "••••••••••••••••" : "");
  }, [apiKey, apiSecret]);

  const handleSave = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      setError(null);
      setSaved(false);

      const trimmedKey = keyInput.trim();
      // secret 若仍是占位符，则保留原值
      const isPlaceholder = secretInput === "••••••••••••••••";
      const finalSecret = isPlaceholder ? apiSecret : secretInput.trim();

      if (!trimmedKey) {
        setError(t("settings.errKeyEmpty"));
        return;
      }
      if (!finalSecret) {
        setError(t("settings.errSecretEmpty"));
        return;
      }
      if (trimmedKey.length < 8) {
        setError(t("settings.errKeyLength"));
        return;
      }
      if (finalSecret.length < 16) {
        setError(t("settings.errSecretLength"));
        return;
      }

      try {
        setCredentials(trimmedKey, finalSecret);
        setSaved(true);
        // 3 秒后清除成功提示
        window.setTimeout(() => setSaved(false), 3000);
      } catch (err) {
        setError(err instanceof Error ? err.message : t("settings.errSaveFailed"));
      }
    },
    [keyInput, secretInput, apiSecret, setCredentials],
  );

  const handleClear = useCallback(() => {
    clearCredentials();
    setKeyInput("");
    setSecretInput("");
    setSaved(false);
    setError(null);
  }, [clearCredentials]);

  const handleSecretFocus = useCallback(() => {
    // 用户聚焦 secret 输入框时，若是占位符则清空等待重新输入
    if (secretInput === "••••••••••••••••") {
      setSecretInput("");
    }
  }, [secretInput]);

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link
            to="/"
            className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <ArrowLeft size={14} />
            {t("settings.back")}
          </Link>
          <h1 className="text-sm font-semibold text-fg-2">{t("settings.title")}</h1>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 py-6 space-y-6">
        {/* 当前认证状态 */}
        <Card>
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-base font-semibold text-fg">{t("settings.authStatus")}</h2>
              <p className="text-xs text-muted mt-1">
                {isAuthenticated
                  ? t("settings.authConfigured")
                  : t("settings.authNotConfigured")}
              </p>
            </div>
            {isAuthenticated ? (
              <span className="flex items-center gap-1.5 text-success text-sm">
                <CheckCircle2 size={16} />
                {t("settings.authenticated")}
              </span>
            ) : (
              <span className="flex items-center gap-1.5 text-muted text-sm">
                <AlertCircle size={16} />
                {t("settings.notAuthenticated")}
              </span>
            )}
          </div>
          {apiKey && (
            <div className="mt-4 pt-4 border-t border-border-soft">
              <div className="text-xs text-muted">{t("settings.currentApiKey")}</div>
              <code className="text-sm text-fg font-mono break-all">{apiKey}</code>
            </div>
          )}
        </Card>

        {/* 凭证输入表单 */}
        <Card>
          <h2 className="text-base font-semibold text-fg mb-1">{t("settings.credentialConfig")}</h2>
          <p className="text-xs text-muted mb-4">
            {t("settings.credentialHint")}
          </p>

          <form onSubmit={handleSave} className="space-y-4">
            {/* API Key */}
            <div>
              <label
                htmlFor="api-key"
                className="block text-xs font-medium text-fg-2 mb-1.5"
              >
                {t("settings.apiKeyLabel")}
              </label>
              <input
                id="api-key"
                type="text"
                value={keyInput}
                onChange={(e) => setKeyInput(e.target.value)}
                placeholder={t("settings.apiKeyPlaceholder")}
                autoComplete="off"
                spellCheck={false}
                className="w-full bg-surface border border-border rounded-md px-3 py-2 text-sm text-fg placeholder-muted focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/30 transition-colors duration-base ease-standard font-mono"
              />
              <p className="text-xs text-muted mt-1">
                {t("settings.apiKeyHeader")}
              </p>
            </div>

            {/* API Secret */}
            <div>
              <label
                htmlFor="api-secret"
                className="block text-xs font-medium text-fg-2 mb-1.5"
              >
                {t("settings.apiSecretLabel")}
              </label>
              <div className="relative">
                <input
                  id="api-secret"
                  type={showSecret ? "text" : "password"}
                  value={secretInput}
                  onChange={(e) => setSecretInput(e.target.value)}
                  onFocus={handleSecretFocus}
                  placeholder={t("settings.apiSecretPlaceholder")}
                  autoComplete="off"
                  spellCheck={false}
                  className="w-full bg-surface border border-border rounded-md px-3 py-2 pr-10 text-sm text-fg placeholder-muted focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/30 transition-colors duration-base ease-standard font-mono"
                />
                <button
                  type="button"
                  onClick={() => setShowSecret((v) => !v)}
                  aria-label={showSecret ? t("settings.hideSecret") : t("settings.showSecret")}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
                >
                  {showSecret ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              <p className="text-xs text-muted mt-1">
                {t("settings.secretHint")}
              </p>
            </div>

            {/* 错误提示 */}
            {error && (
              <div
                role="alert"
                className="flex items-center gap-2 text-sm text-danger bg-surface-2 border border-danger/30 rounded-md px-3 py-2"
              >
                <AlertCircle size={16} />
                {error}
              </div>
            )}

            {/* 成功提示 */}
            {saved && (
              <div
                role="status"
                className="flex items-center gap-2 text-sm text-success bg-surface-2 border border-success/30 rounded-md px-3 py-2"
              >
                <CheckCircle2 size={16} />
                {t("settings.credentialSaved")}
              </div>
            )}

            {/* 操作按钮 */}
            <div className="flex items-center gap-3 pt-2">
              <Button type="submit" variant="primary" size="md">
                {t("settings.save")}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="md"
                onClick={handleClear}
                disabled={!isAuthenticated && !keyInput && !secretInput}
                leadingIcon={<Trash2 size={14} />}
              >
                {t("settings.clear")}
              </Button>
            </div>
          </form>
        </Card>

        {/* 安全说明 */}
        <Card>
          <h2 className="text-base font-semibold text-fg mb-2">{t("settings.securityTitle")}</h2>
          <ul className="text-xs text-fg-2 space-y-1.5 list-disc pl-4">
            <li>{t("settings.security1")}</li>
            <li>{t("settings.security2")}</li>
            <li>{t("settings.security3")}</li>
            <li>{t("settings.security4")}</li>
            <li>{t("settings.security5")}</li>
          </ul>
        </Card>
      </main>
    </div>
  );
};

export default Settings;