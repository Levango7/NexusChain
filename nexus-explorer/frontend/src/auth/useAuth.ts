import { useContext } from "react";
import { AuthContext, type AuthContextValue } from "./AuthContext";

/**
 * Custom hook for consuming the {@link AuthContext}.
 *
 * Must be called within an {@code <AuthProvider>} subtree; throws synchronously
 * otherwise to surface wiring mistakes during development rather than silently
 * returning a degenerate state.
 */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (ctx === null) {
    throw new Error("useAuth must be used within an <AuthProvider>");
  }
  return ctx;
}

export type UseAuthReturn = AuthContextValue;