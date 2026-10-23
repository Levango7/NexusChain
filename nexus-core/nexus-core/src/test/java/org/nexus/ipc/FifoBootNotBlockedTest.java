package org.nexus.ipc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN-031：FIFO IPC 守护线程化后，节点启动不可再被 FIFO 卡死。
 *
 * <p>旧行为（PLAN-027 发现 1）：Linux 上 {@code initFifo} 打开 FIFO 读端
 * 阻塞 main 线程（发生在 ApplicationReadyEvent 之前）⇒ Bootstrapper 永不
 * 执行 ⇒ 不自注册/不出块。生产 distroless（无 mkfifo）走 catch(IOException)
 * 优雅降级恰好幸免——而任何带 mkfifo 的 Linux（WSL/Ubuntu 服务器）都卡死。</p>
 *
 * <p>测试用子类把 {@code isLinuxSystem()} 强制为 true/false（跨平台确定），
 * 断言 {@code run()} 有限时间内返回（不再阻塞 main），以及 worker/开关语义。
 * worker 线程存活性通过 {@code Fifo.lastFifoWorkerThread} 观测（避免线程
 * 枚举的跨用例污染）。</p>
 */
class FifoBootNotBlockedTest {

    /** 模拟 Linux 环境（CI runner 本身是 Linux，但为确定性显式判定）。 */
    static class LinuxFifo extends Fifo {
        @Override
        boolean isLinuxSystem() {
            return true;
        }
    }

    /** 模拟 Windows 环境（显式判定，任何 runner 上语义一致）。 */
    static class WindowsFifo extends Fifo {
        @Override
        boolean isLinuxSystem() {
            return false;
        }
    }

    private static void clearIpcDir() {
        File dir = new File(System.getProperty("user.home") + File.separator + "ipc");
        if (dir.exists()) {
            for (String name : new String[]{"pipe.in", "pipe.out"}) {
                File f = new File(dir, name);
                try {
                    Files.deleteIfExists(f.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** mkfifo 可用性探测（Windows 本机不存在 → 存活性断言类用例 skip）。 */
    private static boolean mkfifoAvailable() {
        try {
            File probe = new File(System.getProperty("user.home"), "ipc-probe-" + System.nanoTime());
            Process p = new ProcessBuilder("mkfifo", probe.getAbsolutePath()).start();
            int code = p.waitFor();
            Files.deleteIfExists(probe.toPath());
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean threadAliveWithin(Thread t, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (t != null && t.isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void linuxRunReturnsBeforeFifoReadable() throws Exception {
        clearIpcDir();
        Fifo fifo = new LinuxFifo();
        fifo.ipcEnabled = true;
        final boolean[] returned = {false};
        Thread caller = new Thread(() -> {
            try {
                fifo.run(null);
                returned[0] = true;
            } catch (Exception e) {
                fail("run() threw on Linux path: " + e);
            }
        }, "fifo-boot-tester");
        caller.start();
        // 旧行为在 worker 缺席时此调用将卡在 initFifo（FileReader open 等待写端）：
        // 守护线程化后 run() 必须立即返回（两平台稳定）
        caller.join(3_000L);
        assertFalse(caller.isAlive(), "run() 必须在 3s 内返回（FIFO 移守护线程）");
        assertTrue(returned[0]);
        clearIpcDir();
    }

    @Test
    void workerThreadRunsOnLinux() throws Exception {
        Assumptions.assumeTrue(mkfifoAvailable(), "mkfifo 不可用的环境跳过存活性断言");
        clearIpcDir();
        Fifo fifo = new LinuxFifo();
        fifo.ipcEnabled = true;
        fifo.run(null);
        assertNotNull(fifo.lastFifoWorkerThread, "Linux 路径必须创建 worker 线程");
        assertTrue(threadAliveWithin(fifo.lastFifoWorkerThread, 2_000L),
                "守护线程 nexus-fifo-ipc 必须在后台运行");
        clearIpcDir();
    }

    @Test
    void disabledIpcNeverSpawnsWorker() throws Exception {
        clearIpcDir();
        Fifo fifo = new LinuxFifo();
        fifo.ipcEnabled = false;
        fifo.run(null);
        assertNull(fifo.lastFifoWorkerThread,
                "nexus.ipc.enabled=false 时必须完全不创建 worker 线程");
        clearIpcDir();
    }

    @Test
    void windowsPathNeverSpawnsWorker() throws Exception {
        Fifo fifo = new WindowsFifo();  // 显式模拟 Windows：非 Linux 不起 worker
        fifo.ipcEnabled = true;
        fifo.run(null);
        assertNull(fifo.lastFifoWorkerThread,
                "Windows（非 Linux）语义不变：不创建 worker 线程");
    }
}
