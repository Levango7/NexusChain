/**
 * 商户资金看板客户端。
 *
 * <h3>为什么调 `/api/v1/fund-dashboard`（不带 merchantId）</h3>
 *
 * 本仓商户侧的身份模型是：`ApiKeyInterceptor` 校验 API Key 并把商户写进
 * `nexus.merchantId`，**客户端不持有也不需要知道自己的 merchantId**。
 * `PaymentController`、`MerchantRoutingProfileController` 都是这个形状。
 *
 * 而 `/api/v1/fund-dashboard/{merchantId}` 那组端点要求路径带 id —— 前端
 * 无从得知自己的 id（全仓没有 `/merchants/me`），所以那组端点对商户控制台
 * 不可用。后端为此补了不带 id 的聚合端点，本模块只调它。
 *
 * <p><b>不要**改回带 id 的路径**：那会要求前端猜/存一个自己不该知道的数字，
 * 而且一旦猜错就是跨商户读取（服务端有归属校验，会 403）。</p>
 */

import { authenticatedRequest } from "./client";

/** 资金概览（`fundOverview`）。金额字段后端用 BigDecimal，序列化为 JSON number。 */
export interface FundOverviewDto {
  balance?: unknown;
  frozen?: unknown;
  reserve?: unknown;
  totalAssets?: unknown;
  /** 账户状态名；账户未建时为 `"NOT_CREATED"`。 */
  balanceStatus?: string;
  /** 风控预警标记（字符串，可能为 null）。 */
  alertFlag?: string | null;
}

/** 单日流水汇总（`transactionTrend[]`）。 */
export interface TrendPointDto {
  /** ISO 日期（`yyyy-MM-dd`）。 */
  date: string;
  totalCredit?: unknown;
  totalDebit?: unknown;
  netFlow?: unknown;
  creditCount?: number;
  debitCount?: number;
}

/** 近期流水（`recentTransactions[]`）。 */
export interface RecentTransactionDto {
  txNo?: string;
  /** 账户操作类型（后端枚举名）。 */
  operationType?: string;
  /** 借贷方向（后端枚举名）。 */
  direction?: string;
  amount?: unknown;
  balanceBefore?: unknown;
  balanceAfter?: unknown;
  reference?: string | null;
  createdAt?: string | null;
}

/** 单条异常流水（`anomalyMonitor.anomalies[]`）。 */
export interface AnomalyDto {
  txNo?: string;
  operationType?: string;
  amount?: unknown;
  balanceAfter?: unknown;
  createdAt?: string | null;
}

/** 异常监控（`anomalyMonitor`）。 */
export interface AnomalyMonitorDto {
  hasAlert?: boolean;
  /** `"NEGATIVE_BALANCE"` 或账户上的 alertFlag 原值。 */
  alertLevel?: string | null;
  anomalyCount?: number;
  anomalies?: AnomalyDto[];
}

/** `GET /api/v1/fund-dashboard` 的完整响应。 */
export interface FundDashboardDto {
  merchantId?: number;
  fundOverview?: FundOverviewDto;
  transactionTrend?: TrendPointDto[];
  recentTransactions?: RecentTransactionDto[];
  anomalyMonitor?: AnomalyMonitorDto;
  generatedAt?: string | null;
}

/**
 * 取当前商户的资金看板（一次请求返回概览 / 趋势 / 明细 / 异常四块）。
 *
 * 服务端按 API Key 推导商户，客户端不传 merchantId。
 */
export function getMyFundDashboard(opts: {
  apiKey: string;
  apiSecret: string;
}): Promise<FundDashboardDto> {
  return authenticatedRequest<FundDashboardDto>("/api/v1/fund-dashboard", {
    apiKey: opts.apiKey,
    apiSecret: opts.apiSecret,
  });
}
