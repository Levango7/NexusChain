import React from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import OrchestrationDashboard from "./pages/OrchestrationDashboard";
import HomePage from "./pages/HomePage";
import BlockDetailPage from "./pages/BlockDetailPage";
import TxDetailPage from "./pages/TxDetailPage";
import AddressPage from "./pages/AddressPage";
import ContractsPage from "./pages/ContractsPage";
import ContractPage from "./pages/ContractPage";
import CrossChainPage from "./pages/CrossChainPage";
import Settings from "./pages/Settings";
import { ErrorBoundary } from "./components/ui";

/**
 * 用 ErrorBoundary 包裹单个页面元素。
 *
 * 顶层 <ErrorBoundary> 兜底整路由树；每条路由再独立包一层，
 * 这样任一页面渲染期错误只影响该页，不会拖垮整站导航 / 其他路由。
 */
const withBoundary = (element: React.ReactElement): React.ReactElement => (
  <ErrorBoundary>{element}</ErrorBoundary>
);

/**
 * App — 顶层路由。
 *
 * 用 ErrorBoundary 包裹整个路由树，避免任一页面渲染期错误导致白屏。
 * 每条路由再独立包一层 ErrorBoundary，单页崩溃不影响其他路由。
 *
 * P2-D3: 新增 /settings 路由，提供运行时 API 凭证配置入口。
 * P1   : 路由级 ErrorBoundary 包裹，隔离单页渲染错误。
 * 2026-10-12: 新增 /contracts、/contracts/:addr、/crosschain —— README 承诺的
 *             「合约查看 / 跨链交易追踪」此前在后端可用但前端零消费（不可达）。
 */
const App: React.FC = () => (
  <ErrorBoundary>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={withBoundary(<HomePage />)} />
        <Route path="/orchestration" element={withBoundary(<OrchestrationDashboard />)} />
        <Route path="/contracts" element={withBoundary(<ContractsPage />)} />
        <Route path="/contracts/:addr" element={withBoundary(<ContractPage />)} />
        <Route path="/crosschain" element={withBoundary(<CrossChainPage />)} />
        <Route path="/block/:height" element={withBoundary(<BlockDetailPage />)} />
        <Route path="/tx/:hash" element={withBoundary(<TxDetailPage />)} />
        <Route path="/address/:addr" element={withBoundary(<AddressPage />)} />
        <Route path="/settings" element={withBoundary(<Settings />)} />
      </Routes>
    </BrowserRouter>
  </ErrorBoundary>
);

export default App;
