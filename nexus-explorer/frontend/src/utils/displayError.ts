import { ApiError } from "../api/client";

/** i18n 翻译函数的最小接口（不把 i18next 的完整类型拖进工具函数）。 */
export type TranslateFn = (key: string) => string;

/**
 * 把任意抛出物映射成**给用户看的**文案（2026-11-07 生产就绪度审计修复，发现 B5）。
 *
 * <h3>修的是什么</h3>
 * <p>各页面原来的写法是：</p>
 * <pre>{@code setError(err instanceof Error ? err.message : t("xx.loadFailed"))}</pre>
 * <p>问题在于**网络失败（`TypeError: Failed to fetch`）、超时（`TimeoutError`）、
 * JSON 解析失败全都是 `Error` 实例** ⇒ 一律走 `err.message` 原文，
 * 而 `t()` 兜底<b>实际永远不可达</b>。结果是中文界面里冒出英文技术串
 * （实测：首页 alert 显示 `Failed to fetch`）。i18n 文案本身是齐的
 * （`common.unknownError` / `xx.loadFailed` 都在），所以这是**接线问题，不是缺文案**。</p>
 *
 * <h3>映射规则</h3>
 * <ol>
 *   <li>{@link ApiError#fromServer} 为真 ⇒ 直接用服务端文案（网关/BFF 已给中文且具体，
 *       如「获取区块列表失败」）。这是**信息量最高**的一类，优先展示。</li>
 *   <li>404 ⇒ {@code common.notFound}；5xx ⇒ {@code common.errorService}。</li>
 *   <li>超时 / 网络不可达 ⇒ 各自专用文案，用户据此判断"是我网络问题还是服务问题"。</li>
 *   <li>其余一律回落调用方的 {@code fallbackKey}（如 {@code home.loadFailed}）。</li>
 * </ol>
 *
 * <p>原始错误细节不在此丢弃：各页面在调用前已有
 * {@code console.error("API请求失败:", err)}，排障信息留在控制台，
 * 这里只负责"人话"。</p>
 *
 * @param err         抛出物（任意类型）
 * @param t           i18n 翻译函数
 * @param fallbackKey 调用方页面的兜底文案键（如 {@code home.loadFailed}）
 * @return 可直接渲染的用户文案
 */
export function displayErrorMessage(err: unknown, t: TranslateFn, fallbackKey: string): string {
  if (err instanceof ApiError) {
    if (err.fromServer && err.message.trim() !== "") {
      return err.message;
    }
    if (err.status === 404) {
      return t("common.notFound");
    }
    if (err.status >= 500) {
      return t("common.errorService");
    }
    // 401/403：公开浏览接口不该出现；若出现说明凭证/路由配置有变，
    // 各页面（如资金看板）另有专门的分支处理，这里只回落。
    return t(fallbackKey);
  }

  // AbortSignal.timeout(...) 拒绝的是一个 name="TimeoutError" 的 DOMException，
  // 手动取消是 "AbortError"。两者都不是 Error 子类的语义问题，按名字判定最稳。
  const name = errorName(err);
  if (name === "TimeoutError") {
    return t("common.errorTimeout");
  }
  if (name === "AbortError") {
    return t("common.errorCanceled");
  }
  // fetch 在连接层失败时抛 TypeError（"Failed to fetch"）——最常见的一类。
  if (err instanceof TypeError) {
    return t("common.errorNetwork");
  }
  return t(fallbackKey);
}

/** 安全读取错误对象的 name（非对象 / 无 name / name 非字符串一律返回空串）。 */
function errorName(err: unknown): string {
  if (err !== null && typeof err === "object") {
    const name = (err as { name?: unknown }).name;
    if (typeof name === "string") {
      return name;
    }
  }
  return "";
}
