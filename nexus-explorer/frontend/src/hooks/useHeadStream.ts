import { useEffect, useRef, useState } from "react";

/**
 * 链头 SSE 订阅 hook（桶 A6 前端件）。
 *
 * 订阅 BFF 的 `/api/events/head`，返回：
 * - `head`：最新链头（init / head 事件驱动）
 * - `connected`：SSE 通道健康状态
 *   - `true`：连接开放且最近收到过 head/init 事件
 *   - `false`：未连接，或连接但 30s 无任何事件（半开检测）
 *   - 语义是「SSE 通路健康」，不是「链正常出块」——链停了 connected 仍为 true，
 *     那是 head.height 停走，由 UI 层另行判断
 *
 * 前端原有的 10s 轮询**保留**作为兜底通路（不因 SSE 存在而删除）：
 * SSE 挂掉时页面照常工作；SSE 正常时 HomePage 可用它跳过冗余拉取。
 */

export interface HeadPayload {
  height: number;
  hash: string;
  ts: number;
}

/** 半开检测：连接开着但这么久没有任何 head/init 事件，视为通路不健康 */
export const STALE_AFTER_MS = 30_000;

export function useHeadStream(url: string = "/api/events/head") {
  const [head, setHead] = useState<HeadPayload | null>(null);
  const [connected, setConnected] = useState(false);
  // 断线重连指数退避：1s → 2s → 4s → … 封顶 30s
  const backoffRef = useRef(1_000);
  const staleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const esRef = useRef<EventSource | null>(null);
  // stale 定时器要能被定时器回调外的地方（卸载/重连）安全清除
  const clearStaleRef = useRef<() => void>(() => {});

  useEffect(() => {
    let disposed = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;

    const clearStaleTimer = () => {
      if (staleTimerRef.current !== null) {
        clearTimeout(staleTimerRef.current);
        staleTimerRef.current = null;
      }
    };
    clearStaleRef.current = clearStaleTimer;

    const armStaleTimer = () => {
      clearStaleTimer();
      staleTimerRef.current = setTimeout(() => {
        // 30s 无事件：视为通路不健康（连接可能半开）
        staleTimerRef.current = null;
        setConnected(false);
      }, STALE_AFTER_MS);
    };

    const handlePayload = (raw: string) => {
      try {
        const payload = JSON.parse(raw) as HeadPayload | null;
        if (payload && typeof payload.height === "number" && typeof payload.hash === "string") {
          setHead(payload);
          setConnected(true);
        }
        armStaleTimer();
      } catch {
        /* 非 JSON：忽略，等下一个事件 */
      }
    };

    const open = () => {
      if (disposed) return;
      const es = new EventSource(url);
      esRef.current = es;

      es.onopen = () => {
        backoffRef.current = 1_000; // 连上即重置退避
        armStaleTimer();
      };

      // EventSource 按事件名分发须用 addEventListener（onmessage 只收无名事件）
      es.addEventListener("init", (ev) => handlePayload((ev as MessageEvent).data));
      es.addEventListener("head", (ev) => handlePayload((ev as MessageEvent).data));

      es.onerror = () => {
        setConnected(false);
        clearStaleTimer();
        // 规范行为：readyState=CONNECTING 时浏览器会自动重连，不干预；
        // 只在 CLOSED（服务器关闭流且浏览器放弃）时手动退避重连
        if (es.readyState === EventSource.CLOSED && !disposed) {
          const delay = backoffRef.current;
          retryTimer = setTimeout(open, delay);
          backoffRef.current = Math.min(backoffRef.current * 2, 30_000);
          es.close();
          esRef.current = null;
        }
      };
    };

    open();

    return () => {
      disposed = true;
      if (retryTimer !== null) clearTimeout(retryTimer);
      clearStaleTimer();
      esRef.current?.close();
      esRef.current = null;
    };
  }, [url]);

  return { head, connected, clearStale: clearStaleRef };
}
