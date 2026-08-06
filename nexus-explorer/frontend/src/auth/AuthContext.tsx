import React, { createContext, useCallback, useMemo, useState } from "react";

/**
 * localStorage keys persisting the merchant API credentials.
 * The secret is stored locally only (browser-side) and sent exclusively
 * as an HMAC-SHA256 signing key — never transmitted in plaintext.
 */
export const API_KEY_STORAGE_KEY = "nexus_api_key";
export const API_SECRET_STORAGE_KEY = "nexus_api_secret";

/**
 * Optional build-time/env fallbacks. Useful for local dev where credentials
 * are injected via Vite env (.env.local) instead of the UI settings page.
 */
const ENV_API_KEY = (import.meta.env.VITE_NEXUS_API_KEY as string | undefined) ?? "";
const ENV_API_SECRET = (import.meta.env.VITE_NEXUS_API_SECRET as string | undefined) ?? "";

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

function readStorage(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    // localStorage may be unavailable (SSR / privacy mode) — degrade gracefully.
    return "";
  }
}

function writeStorage(key: string, value: string): void {
  try {
    if (value) {
      localStorage.setItem(key, value);
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // Ignore write failures — in-memory state remains authoritative.
  }
}

function resolveInitialCredentials(): { apiKey: string; apiSecret: string } {
  const storedKey = readStorage(API_KEY_STORAGE_KEY);
  const storedSecret = readStorage(API_SECRET_STORAGE_KEY);
  // Stored credentials take precedence; env vars are a fallback for dev.
  return {
    apiKey: storedKey || ENV_API_KEY,
    apiSecret: storedSecret || ENV_API_SECRET,
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
    writeStorage(API_KEY_STORAGE_KEY, key);
    writeStorage(API_SECRET_STORAGE_KEY, secret);
  }, []);

  const clearCredentials = useCallback(() => {
    setApiKey("");
    setApiSecret("");
    writeStorage(API_KEY_STORAGE_KEY, "");
    writeStorage(API_SECRET_STORAGE_KEY, "");
  }, []);

  const isAuthenticated = apiKey.length > 0 && apiSecret.length > 0;

  const value = useMemo<AuthContextValue>(
    () => ({ apiKey, apiSecret, isAuthenticated, setCredentials, clearCredentials }),
    [apiKey, apiSecret, isAuthenticated, setCredentials, clearCredentials],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export { AuthContext };