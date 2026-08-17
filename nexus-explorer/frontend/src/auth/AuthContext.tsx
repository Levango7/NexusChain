import React, { createContext, useCallback, useMemo, useState } from "react";

/**
 * localStorage keys persisting the merchant API credentials.
 *
 * 安全模型（P2-D3 修复）：
 *   - API Secret 不再从构建期 env（VITE_NEXUS_API_SECRET）注入，避免烧进 JS bundle。
 *   - 凭证仅由用户在 Settings 页面运行时输入，持久化到 localStorage。
 *   - 存储时使用 base64 编码 + 简单 XOR obfuscation，避免 localStorage 明文泄露。
 *   - Secret 仅作为 HMAC-SHA256 签名 key 使用，绝不以明文传输。
 */
export const API_KEY_STORAGE_KEY = "nexus_api_key";
export const API_SECRET_STORAGE_KEY = "nexus_api_secret";

/**
 * 仅保留 API Key 的 env fallback（开发便利），Secret 必须运行时输入。
 * 生产构建应通过 Settings 页面注入凭证，不再读取 env。
 */
const ENV_API_KEY = (import.meta.env.VITE_NEXUS_API_KEY as string | undefined) ?? "";

export interface AuthContextValue {
  /** Merchant API key (X-NexusChain-ApiKey). */
  apiKey: string;
  /** Merchant API secret used as the HMAC-SHA256 signing key. */
  apiSecret: string;
  /** True when both apiKey and apiSecret are non-empty. */
  isAuthenticated: boolean;
  /** Persist credentials to state + localStorage. */
  setCredentials: (apiKey: string, apiSecret: string) => void;
  /** Clear credentials from state + localStorage. */
  clearCredentials: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * 简单 obfuscation：XOR + base64。
 * 注意：这不是真正的加密（密钥固定），仅用于避免 localStorage 明文肉眼可见。
 * 真正的保护应来自浏览器同源策略 + HTTPS + 用户设备物理安全。
 */
const OBFUSCATION_KEY = "nexus-explorer-v1";

function obfuscate(value: string): string {
  if (!value) return "";
  let out = "";
  for (let i = 0; i < value.length; i++) {
    out += String.fromCharCode(
      value.charCodeAt(i) ^ OBFUSCATION_KEY.charCodeAt(i % OBFUSCATION_KEY.length),
    );
  }
  // btoa 在浏览器环境可用；SSR/测试环境降级为原值
  try {
    return btoa(out);
  } catch {
    return out;
  }
}

function deobfuscate(stored: string): string {
  if (!stored) return "";
  let raw = stored;
  try {
    raw = atob(stored);
  } catch {
    // 非 base64，按原值处理（兼容旧明文存储）
    raw = stored;
  }
  let out = "";
  for (let i = 0; i < raw.length; i++) {
    out += String.fromCharCode(
      raw.charCodeAt(i) ^ OBFUSCATION_KEY.charCodeAt(i % OBFUSCATION_KEY.length),
    );
  }
  return out;
}

function readStorage(key: string, obfuscated: boolean): string {
  try {
    const v = localStorage.getItem(key);
    if (v === null) return "";
    return obfuscated ? deobfuscate(v) : v;
  } catch {
    // localStorage may be unavailable (SSR / privacy mode) — degrade gracefully.
    return "";
  }
}

function writeStorage(key: string, value: string, obfuscated: boolean): void {
  try {
    if (value) {
      localStorage.setItem(key, obfuscated ? obfuscate(value) : value);
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // Ignore write failures — in-memory state remains authoritative.
  }
}

function resolveInitialCredentials(): { apiKey: string; apiSecret: string } {
  const storedKey = readStorage(API_KEY_STORAGE_KEY, false);
  const storedSecret = readStorage(API_SECRET_STORAGE_KEY, true);
  // Stored credentials take precedence; env API Key is a fallback for dev only.
  // Secret 不再从 env 注入，必须由用户运行时输入。
  return {
    apiKey: storedKey || ENV_API_KEY,
    apiSecret: storedSecret,
  };
}

export interface AuthProviderProps {
  children: React.ReactNode;
}

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const initial = resolveInitialCredentials();
  const [apiKey, setApiKey] = useState<string>(initial.apiKey);
  const [apiSecret, setApiSecret] = useState<string>(initial.apiSecret);

  const setCredentials = useCallback((nextKey: string, nextSecret: string) => {
    const key = nextKey ?? "";
    const secret = nextSecret ?? "";
    setApiKey(key);
    setApiSecret(secret);
    writeStorage(API_KEY_STORAGE_KEY, key, false);
    writeStorage(API_SECRET_STORAGE_KEY, secret, true);
  }, []);

  const clearCredentials = useCallback(() => {
    setApiKey("");
    setApiSecret("");
    writeStorage(API_KEY_STORAGE_KEY, "", false);
    writeStorage(API_SECRET_STORAGE_KEY, "", true);
  }, []);

  const isAuthenticated = apiKey.length > 0 && apiSecret.length > 0;

  const value = useMemo<AuthContextValue>(
    () => ({ apiKey, apiSecret, isAuthenticated, setCredentials, clearCredentials }),
    [apiKey, apiSecret, isAuthenticated, setCredentials, clearCredentials],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export { AuthContext };
