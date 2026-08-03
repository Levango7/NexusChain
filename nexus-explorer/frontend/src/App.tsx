import React from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import OrchestrationDashboard from './pages/OrchestrationDashboard';
import HomePage from './pages/HomePage';
import BlockDetailPage from './pages/BlockDetailPage';
import TxDetailPage from './pages/TxDetailPage';
import AddressPage from './pages/AddressPage';

const App: React.FC = () => (
  <BrowserRouter>
    <Routes>
      <Route path="/" element={<HomePage />} />
          <Route path="/orchestration" element={<OrchestrationDashboard />} />
      <Route path="/block/:height" element={<BlockDetailPage />} />
      <Route path="/tx/:hash" element={<TxDetailPage />} />
      <Route path="/address/:addr" element={<AddressPage />} />
    </Routes>
  </BrowserRouter>
);

export default App;