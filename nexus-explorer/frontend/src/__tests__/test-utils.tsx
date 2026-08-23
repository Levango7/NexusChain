import { render, screen, cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import "@testing-library/jest-dom";
// 初始化 i18n，使 useTranslation 在测试中可用
import "../i18n";

/**
 * 测试工具模块 — 统一封装 @testing-library/react 的 render / screen，
 * 并在每个测试用例后自动 cleanup，避免组件状态泄漏。
 *
 * 用法：
 *   import { describe, it, expect } from 'vitest';
 *   import { render, screen } from '../test-utils';
 *   import { Button } from '../../components/ui/Button';
 */
afterEach(() => {
  cleanup();
});

export { render, screen };
