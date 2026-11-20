import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent } from "@testing-library/react";
import { randomUUID } from "node:crypto";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../../auth/AuthContext";
import Settings from "../../pages/Settings";
import { render, screen } from "../test-utils";

function installLocalStorage() {
  const values = new Map<string, string>();
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    writable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, String(value)),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
      key: (index: number) => Array.from(values.keys())[index] ?? null,
      get length() {
        return values.size;
      },
    },
  });
}

describe("Settings 成功提示定时器", () => {
  beforeEach(() => {
    installLocalStorage();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it("重复保存只保留一个定时器，卸载时全部清理", () => {
    const view = render(
      <MemoryRouter>
        <AuthProvider>
          <Settings />
        </AuthProvider>
      </MemoryRouter>,
    );
    fireEvent.change(screen.getByLabelText("API Key"), {
      target: { value: randomUUID().replace(/-/g, "") },
    });
    fireEvent.change(screen.getByLabelText("API Secret"), {
      target: { value: randomUUID().replace(/-/g, "") },
    });
    const save = screen.getByRole("button", { name: "保存凭证" });

    fireEvent.click(save);
    fireEvent.click(save);
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(1);

    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      vi.runAllTimers();
    });
  });
});
