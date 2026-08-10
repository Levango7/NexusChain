package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;

import java.util.List;

/**
 * MPC 协议传输层抽象。
 *
 * <p>GG18/GG20 协议在每一轮中需要在参与者之间交换点对点消息与广播消息。
 * 该接口屏蔽底层传输实现（内存直连 / HTTP / gRPC），让上层协议逻辑
 * 仅依赖 {@link MpcMessage} 这一中立消息抽象。</p>
 *
 * <p><b>实现策略</b>：</p>
 * <ul>
 *   <li>{@link InMemoryMpcTransport}：进程内单 JVM 测试与 composite build
 *       占位实现，所有参与者位于同一 JVM。</li>
 *   <li>{@link GrpcMpcTransportStub}：gRPC over HTTP/2 占位实现，定义真实
 *       gRPC 切换所需的全部方法签名，但当前以 HTTP/内存回退。后续接入
 *       io.grpc:grpc-stub 时只需替换 {@code send}/{@code receive} 内部实现，
 *       接口与消息类型保持不变。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：实现必须保证 {@code send} 与 {@code receive} 可被
 * 不同线程并发调用（一轮中多个参与者并发发送），但单个会话内同一参与者
 * 不应并发发送同一轮次消息。</p>
 */
public interface MpcTransport {

    /**
     * 启动传输层并建立与所有参与者的连接。
     *
     * @param participants 参与者列表（含 endpoint 信息）
     * @throws MpcProtocolException 若无法建立连接（含 QUORUM_NOT_REACHED）
     */
    void connect(List<MpcParticipant> participants);

    /**
     * 发送一条点对点消息给指定接收者。
     *
     * @param message 待发送消息（含接收者 ID）
     * @throws MpcProtocolException 若发送失败（网络中断、对端不可达）
     */
    void send(MpcMessage message);

    /**
     * 阻塞接收一条消息，直到指定轮次内收到来自 {@code fromParticipantId}
     * 的消息或超时。
     *
     * @param sessionId        会话 ID
     * @param round            轮次号（1-based）
     * @param fromParticipantId 期望发送者 ID
     * @param timeoutMillis    超时（毫秒）
     * @return 接收到的消息
     * @throws MpcProtocolException 若超时（{@link MpcProtocolException.Reason#TIMEOUT}）
     */
    MpcMessage receive(String sessionId, int round, String fromParticipantId, long timeoutMillis);

    /**
     * 关闭传输层并释放底层资源（socket / channel / executor）。
     */
    void close();

    /**
     * @return 传输层是否已建立连接
     */
    boolean isConnected();
}