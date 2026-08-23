import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, waitFor } from "@testing-library/react";
import { render, screen } from "../test-utils";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../../auth/AuthContext";
import { useAuth } from "../../auth/useAuth";
import Settings from "../../pages/Settings";

/**
 * Settings 占位符碰撞测试（P2-D3 修复专项）。
 *
 * 验证修复：旧实现通过判断 secretInput === "••••••••••••••••" 来识别占位符状态，
 * 当用户实际输入相同字符串时会被误判为占位符，导致保存时使用旧 secret 而非用户输入。
 *
 * 修复：使用独立的 boolean state `secretIsPlaceholder` 跟踪占位符状态，
 * 避免字符串碰撞。
 */

// 确保 localStorage 可用且具有标准接口（jsdom 环境兼容）
function ensureLocalStorage() {
  const store = new Map<string, string>();
  const ls = {
    getItem: (key: string) => store.has(key) ? store.get(key)! : null,
    setItem: (key: string, value: string) => store.set(key, String(value)),
    removeItem: (key: string) => store.delete(key),
    clear: () => store.clear(),
    key: (i: number) => Array.from(store.keys())[i] ?? null,
    get length() { return store.size; },
  };
  Object.defineProperty(globalThis, "localStorage", {
    value: ls,
    writable: true,
    configurable: true,
  });
}

const SECRET_MASK = "••••••••••••••••";

// 收集 auth API 的 probe 组件
let authApi: {
  apiKey: string;
  apiSecret: string;
  isAuthenticated: boolean;
  setCredentials: (k: string, s: string) => void;
  clearCredentials: () => void;
} | null = null;

function AuthProbe() {
  const auth = useAuth();
  authApi = auth;
  return null;
}

function renderSettings() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <AuthProbe />
        <Settings />
      </AuthProvider>
    </MemoryRouter> as ReactNode,
  );
}

describe("Settings 占位符碰撞测试（P2-D3 修复）", () => {
  beforeEach(() => {
    ensureLocalStorage();
    localStorage.clear();
    authApi = null;
  });

  afterEach(() => {
    localStorage.clear();
    authApi = null;
  });

  it("未认证时 secret 输入框应为空（非占位符）", () => {
    renderSettings();
    const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
    // 未认证时应为空，不是占位符
    if (secretInput) {
      expect(secretInput.value).toBe("");
    }
  });

  it("已认证时 secret 输入框应显示占位符掩码", async () => {
    renderSettings();

    // 等待 authApi 可用
    await waitFor(() => expect(authApi).not.toBeNull());

    // 设置凭证
    act(() => {
      authApi!.setCredentials("test-api-key", "test-secret-12345678");
    });

    // 等待 Settings 重新渲染并显示占位符
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput).not.toBeNull();
      expect(secretInput!.value).toBe(SECRET_MASK);
    });
  });

  it("占位符状态下保存应保留原 secret（不误判为占位符碰撞）", async () => {
    const user = userEvent.setup();
    const originalSecret = "original-secret-12345678";

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("test-api-key-1", originalSecret);
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    // 不修改 secret，直接点击保存
    const saveButton = await screen.findByRole("button", { name: /保存凭证/ });
    await user.click(saveButton);

    // 关键断言：保存后 secret 应仍为原值（占位符状态保留原 secret）
    await waitFor(() => {
      expect(authApi!.apiSecret).toBe(originalSecret);
    });
  });

  it("用户输入与占位符相同的字符串应被视为真实输入", async () => {
    // 关键测试：用户输入 "••••••••••••••••" 应被视为真实输入，而非占位符
    const user = userEvent.setup();

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("test-api-key-2", "initial-secret-12345678");
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement;

    // 聚焦清空占位符
    await user.click(secretInput);
    expect(secretInput.value).toBe("");

    // 输入与占位符相同的字符串
    await user.type(secretInput, SECRET_MASK);
    expect(secretInput.value).toBe(SECRET_MASK);

    // 修改 apiKey
    const keyInput = screen.queryByLabelText(/API Key/i) as HTMLInputElement;
    await user.clear(keyInput);
    await user.type(keyInput, "new-api-key-12345");

    // 点击保存
    const saveButton = screen.getByRole("button", { name: /保存凭证/ });
    await user.click(saveButton);

    // 关键断言：保存后 secret 应为用户输入的 "••••••••••••••••"（真实输入）
    // 而非旧的 "initial-secret-12345678"（占位符误判）
    await waitFor(() => {
      expect(authApi!.apiSecret).toBe(SECRET_MASK);
    });
  });

  it("用户修改 secret 后保存应使用新 secret", async () => {
    const user = userEvent.setup();
    const newSecret = "new-secret-abcdef-123456";

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("test-api-key-3", "old-secret-12345678");
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement;

    // 聚焦清空占位符
    await user.click(secretInput);
    expect(secretInput.value).toBe("");

    // 输入新 secret
    await user.type(secretInput, newSecret);

    // 点击保存
    const saveButton = screen.getByRole("button", { name: /保存凭证/ });
    await user.click(saveButton);

    // 保存后 secret 应为新值
    await waitFor(() => {
      expect(authApi!.apiSecret).toBe(newSecret);
    });
  });

  it("清除凭证后 secret 输入框应为空", async () => {
    const user = userEvent.setup();

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("key-to-clear-1", "secret-to-clear-12345");
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    // 点击清除
    const clearButton = screen.getByRole("button", { name: /清除凭证/ });
    await user.click(clearButton);

    // 清除后 secret 输入框应为空
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe("");
    });
  });

  it("占位符状态下聚焦应清空输入框等待重新输入", async () => {
    const user = userEvent.setup();

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("key-focus-test", "secret-focus-12345678");
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement;

    // 聚焦应清空
    await user.click(secretInput);
    expect(secretInput.value).toBe("");
  });

  it("用户输入后不应再处于占位符状态", async () => {
    const user = userEvent.setup();

    renderSettings();
    await waitFor(() => expect(authApi).not.toBeNull());

    act(() => {
      authApi!.setCredentials("key-state-test", "secret-state-12345678");
    });

    // 等待占位符显示
    await waitFor(() => {
      const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement | null;
      expect(secretInput?.value).toBe(SECRET_MASK);
    });

    const secretInput = screen.queryByLabelText(/API Secret/i) as HTMLInputElement;

    // 聚焦清空
    await user.click(secretInput);
    // 输入部分字符
    await user.type(secretInput, "partial-input-12345");

    // 点击保存
    const saveButton = screen.getByRole("button", { name: /保存凭证/ });
    await user.click(saveButton);

    // 保存后 secret 应为用户输入，而非原值
    await waitFor(() => {
      expect(authApi!.apiSecret).toBe("partial-input-12345");
    });
  });
});
