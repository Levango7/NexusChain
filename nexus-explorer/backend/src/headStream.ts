/**
 * NexusChain Explorer — 链头广播器（桶 A6 后端件）
 *
 * 职责：单实例轮询 nexus-core 节点状态，检测链头（latestHeight/latestHash）
 * 变化后向全部 SSE 订阅者广播 head 事件。
 *
 * 设计要点：
 * - **不管多少个浏览器订阅，节点侧始终只有一条轮询流** —— 订阅者只是挂在一个
 *   内存 Set 上，避免 N 个客户端 = N 倍 RPC 压力（B6 审计同款关切）。
 * - 首次成功取到状态前不广播（避免把「未初始化」当事件推给前端）。
 * - 轮询周期由 `EXPLORER_HEAD_POLL_MS` 配置（默认 3000，钳制在 500ms~60s）——
 *   这仍是轮询，只是把「每个浏览器各自 10s 轮询」收敛为「服务端一处轮询 +
 *   变化才推送」，前端从「固定节奏拉」变成「事件驱动刷」。
 */

import type { NexusChainRpcClient } from "./rpc.js";

/** head 事件负载（SSE `event: head` 的 data 字段） */
export interface HeadEvent {
  height: number;
  hash: string;
  /** 服务端产生该事件的时间戳（epoch ms） */
  ts: number;
}

type Subscriber = (e: HeadEvent) => void;

export class HeadBroadcaster {
  private readonly rpc: NexusChainRpcClient;
  private readonly pollMs: number;
  private readonly subscribers = new Set<Subscriber>();
  private lastHeight: number | null = null;
  private lastHash: string | null = null;
  private lastHead: HeadEvent | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private pollInFlight = false;
  /** 供测试注入；生产走全局 console */
  private readonly logError: (msg: string, err: unknown) => void;

  constructor(
    rpc: NexusChainRpcClient,
    opts?: { pollMs?: number; logError?: (msg: string, err: unknown) => void },
  ) {
    this.rpc = rpc;
    this.logError = opts?.logError ?? ((msg, err) => console.error(msg, err));
    const requested = opts?.pollMs ?? Number(process.env.EXPLORER_HEAD_POLL_MS ?? 3000);
    // 下限 500ms：防误配 1ms 打爆节点；上限 60s：配大了就失去「实时」意义
    this.pollMs = Math.min(Math.max(Number.isFinite(requested) ? requested : 3000, 500), 60_000);
  }

  /** 当前已知链头（尚未探测到任何状态时为 null）。SSE 接入时先发这个。 */
  currentHead(): HeadEvent | null {
    return this.lastHead;
  }

  subscriberCount(): number {
    return this.subscribers.size;
  }

  /**
   * 注册订阅者。返回取消订阅函数（幂等：重复调用安全）。
   * 首个订阅者到达时自动启动轮询，最后一个离开时自动停止 ——
   * 没有观众时不打节点。
   */
  subscribe(fn: Subscriber): () => void {
    this.subscribers.add(fn);
    this.ensurePolling();
    return () => {
      this.subscribers.delete(fn);
      if (this.subscribers.size === 0) this.stopPolling();
    };
  }

  /** 单次探测（公开供测试驱动；生产由定时器调用）。 */
  async pollOnce(): Promise<void> {
    // 上一次探测还没回来时跳过本轮 —— 节点慢响应不该堆积并发请求
    if (this.pollInFlight) return;
    this.pollInFlight = true;
    try {
      const status = await this.rpc.getNodeStatus();
      const height = status?.latestHeight;
      const hash = status?.latestHash;
      if (typeof height !== "number" || typeof hash !== "string") return;
      // 同高度但哈希变化（极端场景：reorg）也要推
      if (height === this.lastHeight && hash === this.lastHash) return;
      this.lastHeight = height;
      this.lastHash = hash;
      this.lastHead = { height, hash, ts: Date.now() };
      this.broadcast(this.lastHead);
    } catch (err) {
      // 节点不可达是常态（同步/重启/网络抖动）：记日志、保持静默，
      // 前端靠 SSE 断线检测 + 原有轮询兜底
      this.logError("链头探测失败（节点不可达或 RPC 异常）:", err);
    } finally {
      this.pollInFlight = false;
    }
  }

  private broadcast(e: HeadEvent): void {
    for (const fn of this.subscribers) {
      try {
        fn(e);
      } catch (err) {
        // 单个订阅者写失败（如已断开的连接）不影响其他人
        this.logError("head 事件投递给单个订阅者失败:", err);
      }
    }
  }

  private ensurePolling(): void {
    if (this.timer !== null) return;
    // 启动即探一次，让首个订阅者立刻拿到链头（而不是等一个周期）
    void this.pollOnce();
    this.timer = setInterval(() => void this.pollOnce(), this.pollMs);
    // 定时器不应阻止进程退出
    if (typeof this.timer === "object" && "unref" in this.timer) {
      this.timer.unref();
    }
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
