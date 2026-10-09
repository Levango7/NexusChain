package org.nexus.signing.mpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CGGMP21 的「参与方 = 引擎端点」推导器（2026-10-09，keychain 供给/入口层配套）。
 *
 * <p>GG20 退役后每台 mpc-engine = 一个 MPC 参与方，参与方集合等价于
 * {@code mpc.engine.endpoints} 配置的引擎列表（生产 = StatefulSet 逐 Pod headless DNS；
 * 沙箱 = {@code mpc-engine-0/1/2:50051}）。本组件把该配置翻译成
 * {@link MpcParticipant} 列表，供：</p>
 * <ul>
 *   <li>{@code DefaultMpcService.generateKeyShare} 的 DKG 编排（n/t 与端点），</li>
 *   <li>{@code MpcApprovalPolicy.canSign} 的业务审批法定数（在线参与方计数），</li>
 * </ul>
 * <p>两条口径必须分清（勿混）：**加密阈值** t（引擎侧 t-of-n，如 2-of-3）与
 * **业务审批法定数**（{@code MpcApprovalPolicy} 冷钱包策略，如 3-of-5）是不同维度，
 * 前者决定签名可行性，后者决定"这笔转账是否被批准"。</p>
 *
 * <p>端点串与 {@code MpcEngineRouter} 同源同格式（{@code host:port}，逗号分隔；
 * 为空时回退 {@code mpc.engine.host:port} 单端点）。</p>
 */
@Component
public class MpcEngineParticipants {

    private static final Logger log = LoggerFactory.getLogger(MpcEngineParticipants.class);

    /** 引擎端点列表（host:port，逗号分隔；与 MpcEngineRouter 同一配置键）。 */
    @Value("${mpc.engine.endpoints:}")
    private String endpoints;

    /** 单端点回退（endpoints 为空时）。 */
    @Value("${mpc.engine.host:localhost}")
    private String host;

    @Value("${nexus.mpc.engine.port:50051}")
    private int port;

    /**
     * 按引擎端点顺序推导参与方列表（participantId = {@code party-<index>}，
     * 与引擎侧 party_id 约定一致；{@code publicKeyShareHex} 对 CGGMP21 无意义——
     * 份额驻留引擎，恒为空串）。
     */
    public List<MpcParticipant> participants() {
        List<String> eps = new ArrayList<>();
        if (endpoints != null && !endpoints.isBlank()) {
            for (String e : endpoints.split(",")) {
                if (!e.isBlank()) {
                    eps.add(e.trim());
                }
            }
        }
        if (eps.isEmpty()) {
            eps.add(host + ":" + port);
        }
        List<MpcParticipant> out = new ArrayList<>(eps.size());
        for (int i = 0; i < eps.size(); i++) {
            out.add(new MpcParticipant("party-" + i, eps.get(i), ""));
        }
        log.debug("MPC engine participants derived: count={}, endpoints={}", out.size(), eps);
        return out;
    }

    /** 参与方数 n（= 引擎端点数）。 */
    public int totalParties() {
        return participants().size();
    }
}
