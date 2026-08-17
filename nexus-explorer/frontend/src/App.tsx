import React from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import OrchestrationDashboard from "./pages/OrchestrationDashboard";
import HomePage from "./pages/HomePage";
import BlockDetailPage from "./pages/BlockDetailPage";
import TxDetailPage from "./pages/TxDetailPage";
import AddressPage from "./pages/AddressPage";
import Settings from "./pages/Settings";
import { ErrorBoundary } from "./components/ui";

/**
 * App — 顶层路由。
 *
 * 用 ErrorBoundary 包裹整个路由树，避免任一页面渲染期错误导致白屏。
 *
 * P2-D3: 新增 /settings 路由，提供运行时 API 凭证配置入口。
 */
const App: React.FC = () => (
  <ErrorBoundary>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/orchestration" element={<OrchestrationDashboard />} />
        <Route path="/block/:height" element={<BlockDetailPage />} />
        <Route path="/tx/:hash" element={<TxDetailPage />} />
        <Route path="/address/:addr" element={<AddressPage />} />
        <Route path="/settings" element={<Settings />} />
      </Routes>
    </BrowserRouter>
  </ErrorBoundary>
);

export default App;
