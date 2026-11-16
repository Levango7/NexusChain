import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { useHeadStream, STALE_AFTER_MS } from "../../hooks/useHeadStream";

/**
 * useHeadStream 单测（桶 A6 前端件）。
 *
 * 策略：mock 全局 EventSource，手工驱动 init/head/error 事件，
 * 断言 hook 的 head/connected 状态机与半开检测逻辑。
 */

type Listener = (ev: MessageEvent) => void;

/** EventSource 测试替身：记录实例、允许手工派发事件 */
class MockEventSource {
  static instances: MockEventSource[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  url: string;
  readyState = MockEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, Set<Listener>>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, fn: Listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(fn);
  }

  emit(type: string, data: unknown) {
    const ev = { data: JSON.stringify(data) } as MessageEvent;
    this.listeners.get(type)?.forEach((fn) => fn(ev));
  }

  emitRaw(type: string, data: string) {
    const ev = { data } as MessageEvent;
    this.listeners.get(type)?.forEach((fn) => fn(ev));
  }

  open() {
    this.readyState = MockEventSource.OPEN;
    this.onopen?.();
  }

  failAsClosed() {
    this.readyState = MockEventSource.CLOSED;
    this.onerror?.();
  }

  close() {
    this.readyState = MockEventSource.CLOSED;
  }
}

describe("useHeadStream", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    MockEventSource.instances = [];
    vi.stubGlobal("EventSource", MockEventSource as unknown as typeof EventSource);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("挂载即建立连接，init 事件设置 head 并置 connected=true", async () => {
    const { result } = renderHook(() => useHeadStream("/api/events/head"));
    const es = MockEventSource.instances[0];
    expect(es).toBeDefined();
    expect(es.url).toBe("/api/events/head");

    act(() => {
      es.open();
      es.emit("init", { height: 42, hash: "0xabc", ts: 1_000 });
    });

    expect(result.current.head).toEqual({ height: 42, hash: "0xabc", ts: 1_000 });
    expect(result.current.connected).toBe(true);
  });

  it("head 事件更新 head；同高度重复事件不影响", async () => {
    const { result } = renderHook(() => useHeadStream());
    const es = MockEventSource.instances[0];
    act(() => {
      es.open();
      es.emit("init", { height: 42, hash: "0xabc", ts: 1 });
    });
    act(() => {
      es.emit("head", { height: 43, hash: "0xdef", ts: 2 });
    });
    expect(result.current.head?.height).toBe(43);
    expect(result.current.connected).toBe(true);

    // 重复同高事件：仍更新（head 对象整体替换），connected 不变
    act(() => {
      es.emit("head", { height: 43, hash: "0xdef", ts: 3 });
    });
    expect(result.current.head?.ts).toBe(3);
  });

  it("非 JSON 负载被忽略，不影响已建立的 connected 状态", async () => {
    const { result } = renderHook(() => useHeadStream());
    const es = MockEventSource.instances[0];
    act(() => {
      es.open();
      es.emit("init", { height: 1, hash: "0x1", ts: 1 });
    });
    expect(result.current.connected).toBe(true);
    act(() => {
      es.emitRaw("head", "not-json{");
    });
    expect(result.current.head?.height).toBe(1);
    expect(result.current.connected).toBe(true);
  });

  it("字段缺失的负载（height 非数字）被拒绝", async () => {
    const { result } = renderHook(() => useHeadStream());
    const es = MockEventSource.instances[0];
    act(() => {
      es.open();
    });
    act(() => {
      es.emit("head", { height: "42", hash: "0xabc" });
    });
    expect(result.current.head).toBeNull();
    expect(result.current.connected).toBe(false);
  });

  it("error 且 readyState=CLOSED 时按退避重连（1s → 2s），open 后重置退避", async () => {
    const { result } = renderHook(() => useHeadStream());
    expect(MockEventSource.instances).toHaveLength(1);
    const es1 = MockEventSource.instances[0];
    act(() => {
      es1.open();
      es1.emit("init", { height: 1, hash: "0x1", ts: 1 });
    });
    expect(result.current.connected).toBe(true);

    act(() => {
      es1.failAsClosed();
    });
    expect(result.current.connected).toBe(false);

    // 1s 退避后重连出第二个实例
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(MockEventSource.instances).toHaveLength(2);
    const es2 = MockEventSource.instances[1];

    // 再断一次 → 这次退避应为 2s
    act(() => {
      es2.failAsClosed();
    });
    act(() => {
      vi.advanceTimersByTime(1_500);
    });
    // 1.5s < 2s：尚未重连
    expect(MockEventSource.instances).toHaveLength(2);
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(MockEventSource.instances).toHaveLength(3);

    // 连上后重置退避：断开 → 1s 即重连
    const es3 = MockEventSource.instances[2];
    act(() => {
      es3.open();
      es3.failAsClosed();
    });
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(MockEventSource.instances).toHaveLength(4);
  });

  it("半开检测：连接开着但 30s 无事件 → connected 翻 false；事件刷新计时", async () => {
    const { result } = renderHook(() => useHeadStream());
    const es = MockEventSource.instances[0];
    act(() => {
      es.open();
      es.emit("init", { height: 1, hash: "0x1", ts: 1 });
    });
    expect(result.current.connected).toBe(true);

    // 29s：仍健康
    act(() => {
      vi.advanceTimersByTime(29_000);
    });
    expect(result.current.connected).toBe(true);

    // 再过 2s（超 30s 无事件）→ 翻 false
    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    expect(result.current.connected).toBe(false);

    // 事件到来重新武装计时器并翻回 true
    act(() => {
      es.emit("head", { height: 2, hash: "0x2", ts: 2 });
    });
    expect(result.current.connected).toBe(true);
  });

  it("卸载时关闭连接，不再重连", async () => {
    const { result, unmount } = renderHook(() => useHeadStream());
    const es = MockEventSource.instances[0];
    act(() => {
      es.open();
      es.emit("init", { height: 1, hash: "0x1", ts: 1 });
    });
    expect(result.current.connected).toBe(true);
    unmount();
    expect(es.readyState).toBe(MockEventSource.CLOSED);
    act(() => {
      es.failAsClosed();
    });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it("环境无 EventSource（jsdom/旧浏览器）时优雅降级：不抛错、connected 恒 false、不重试", async () => {
    vi.unstubAllGlobals();
    // jsdom 本身没有 EventSource；stub 后再撤掉即模拟"未定义"环境
    const { result } = renderHook(() => useHeadStream());
    expect(result.current.head).toBeNull();
    expect(result.current.connected).toBe(false);
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    // 不重试、不崩溃——HomePage 的 10s 轮询兜底通路接管
    expect(result.current.connected).toBe(false);
    expect(result.current.head).toBeNull();
  });
});
