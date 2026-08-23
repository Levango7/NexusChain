import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act } from "@testing-library/react";
import { render, screen } from "../test-utils";
import type { ReactNode } from "react";
import {
  AuthProvider,
  API_KEY_STORAGE_KEY,
  API_SECRET_STORAGE_KEY,
} from "../../auth/AuthContext";
import { useAuth } from "../../auth/useAuth";

/**
 * AuthContext btoa 编码测试（P2-D3 修复专项）。
 *
 * 验证非 ASCII 字符（如中文）的 base64 编码/解码正确性。
 *
 * 背景：旧实现使用 btoa(out) 直接编码 XOR 后的字符串，
 * 但 XOR 可能产生非 Latin1 字符，导致 btoa 抛
 * "InvalidCharacterError: The string to be encoded contains characters outside of the Latin1 range."
 *
 * 修复：使用 btoa(unescape(encodeURIComponent(out))) 将 UTF-8 字节序列
 * 转为 Latin1 字符串后再 base64，解码时用 decodeURIComponent(escape(atob(stored)))。
 *
 * 测试策略：通过 AuthProvider 的 setCredentials / getApiKey / getApiSecret
 * 间接测试 obfuscate / deobfuscate 的编解码正确性。
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

function ProbeComponent({
  onReady,
}: {
  onReady: (api: {
    apiKey: string;
    apiSecret: string;
    isAuthenticated: boolean;
    setCredentials: (k: string, s: string) => void;
    clearCredentials: () => void;
  }) => void;
}) {
  const auth = useAuth();
  // 在首次渲染时把 auth 接口暴露出去
  if (onReady) onReady(auth);
  return null;
}

function renderAuthProvider() {
  let authApi: {
    apiKey: string;
    apiSecret: string;
    isAuthenticated: boolean;
    setCredentials: (k: string, s: string) => void;
    clearCredentials: () => void;
  } | null = null;

  const onReady = vi.fn((api: typeof authApi) => {
    authApi = api;
  });

  const view = render(
    <AuthProvider>
      <ProbeComponent onReady={(api) => onReady(api)} />
    </AuthProvider> as ReactNode,
  );

  return {
    view,
    getAuth: () => authApi!,
    onReady,
  };
}

describe("AuthContext btoa 编码（P2-D3 修复）", () => {
  beforeEach(() => {
    ensureLocalStorage();
    // 清空 localStorage
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it("应该正确编码/解码纯 ASCII 字符的 secret", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    const apiKey = "nexus-api-key-12345";
    const apiSecret = "ascii-secret-abcdef123456";

    act(() => {
      auth.setCredentials(apiKey, apiSecret);
    });

    // 重新渲染获取最新值
    const { getAuth: getAuth2 } = renderAuthProvider();
    const auth2 = getAuth2();

    expect(auth2.apiKey).toBe(apiKey);
    expect(auth2.apiSecret).toBe(apiSecret);
    expect(auth2.isAuthenticated).toBe(true);
  });

  it("应该正确编码/解码包含中文字符的 secret", () => {
    // 关键测试：中文字符是非 ASCII，旧 btoa 实现会抛 InvalidCharacterError
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    const apiKey = "nexus-key-chinese";
    const apiSecret = "中文密钥-测试-12345678";

    act(() => {
      auth.setCredentials(apiKey, apiSecret);
    });

    // 验证 localStorage 中存储的是 base64 编码（非明文）
    const stored = localStorage.getItem(API_SECRET_STORAGE_KEY);
    expect(stored).toBeTruthy();
    expect(stored).not.toContain(apiSecret);
    expect(stored).not.toContain("中文");

    // 重新渲染应能正确解码
    const { getAuth: getAuth2 } = renderAuthProvider();
    const auth2 = getAuth2();

    expect(auth2.apiKey).toBe(apiKey);
    expect(auth2.apiSecret).toBe(apiSecret);
    expect(auth2.isAuthenticated).toBe(true);
  });

  it("应该正确编码/解码包含 emoji 的 secret", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    const apiKey = "key-with-emoji";
    const apiSecret = "secret-🔑-password-123456";

    act(() => {
      auth.setCredentials(apiKey, apiSecret);
    });

    const { getAuth: getAuth2 } = renderAuthProvider();
    const auth2 = getAuth2();

    expect(auth2.apiKey).toBe(apiKey);
    expect(auth2.apiSecret).toBe(apiSecret);
  });

  it("应该正确编码/解码包含特殊 Unicode 字符的 secret", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    // 各种特殊 Unicode 字符
    const apiKey = "key-unicode";
    const apiSecret = "密码-Ñoño-日本語-한국어-12345678";

    act(() => {
      auth.setCredentials(apiKey, apiSecret);
    });

    const { getAuth: getAuth2 } = renderAuthProvider();
    const auth2 = getAuth2();

    expect(auth2.apiKey).toBe(apiKey);
    expect(auth2.apiSecret).toBe(apiSecret);
    expect(auth2.isAuthenticated).toBe(true);
  });

  it("应该正确处理空字符串 secret", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    act(() => {
      auth.setCredentials("some-key", "");
    });

    const { getAuth: getAuth2 } = renderAuthProvider();
    const auth2 = getAuth2();

    expect(auth2.apiKey).toBe("some-key");
    expect(auth2.apiSecret).toBe("");
    expect(auth2.isAuthenticated).toBe(false);
  });

  it("clearCredentials 后应清除 localStorage 和内存状态", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    act(() => {
      auth.setCredentials("key-to-clear", "中文-secret-12345678");
    });

    expect(localStorage.getItem(API_KEY_STORAGE_KEY)).toBeTruthy();
    expect(localStorage.getItem(API_SECRET_STORAGE_KEY)).toBeTruthy();

    act(() => {
      auth.clearCredentials();
    });

    expect(localStorage.getItem(API_KEY_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(API_SECRET_STORAGE_KEY)).toBeNull();
  });

  it("应该不将 secret 明文存储到 localStorage", () => {
    const { getAuth } = renderAuthProvider();
    const auth = getAuth();

    const apiSecret = "plaintext-detectable-secret-12345";
    act(() => {
      auth.setCredentials("key", apiSecret);
    });

    const stored = localStorage.getItem(API_SECRET_STORAGE_KEY);
    expect(stored).toBeTruthy();
    // 关键断言：localStorage 中不应包含明文 secret
    expect(stored).not.toContain(apiSecret);
    expect(stored).not.toContain("plaintext-detectable");
  });

  it("应该能往返编解码各种边界字符", () => {
    const testCases = [
      "a", // 单字符
      "ab", // 双字符
      "1234567890123456", // 16 字符
      "🎉🎊🎈", // 多个 emoji
      "中文测试密钥", // 纯中文
      "mixed-中文-ascii-🔑-end", // 混合
      "\u0000\u0001\u007F", // 控制字符 + DEL
      "a".repeat(100), // 长字符串
    ];

    for (const secret of testCases) {
      localStorage.clear();
      const { getAuth } = renderAuthProvider();
      const auth = getAuth();

      act(() => {
        auth.setCredentials("key-boundary", secret);
      });

      const { getAuth: getAuth2 } = renderAuthProvider();
      const auth2 = getAuth2();

      expect(auth2.apiSecret).toBe(secret);
    }
  });

  it("XOR + base64 编码后应能正确解码（obfuscate/deobfuscate 互逆）", () => {
    // 通过 setCredentials -> 重新加载 验证互逆性
    const secrets = [
      "test-secret-12345678",
      "中文-secret-12345678",
      "🔑-secret-12345678",
      "mixed-中文-🔑-12345678",
    ];

    for (const secret of secrets) {
      localStorage.clear();
      const { getAuth } = renderAuthProvider();
      act(() => {
        getAuth().setCredentials("xor-key", secret);
      });

      const { getAuth: getAuth2 } = renderAuthProvider();
      // 互逆性：deobfuscate(obfuscate(x)) == x
      expect(getAuth2().apiSecret).toBe(secret);
    }
  });
});