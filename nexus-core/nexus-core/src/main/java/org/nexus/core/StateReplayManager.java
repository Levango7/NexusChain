package org.nexus.core;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.codec.DecoderException;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.event.StateRootMismatchEvent;
import org.nexus.core.incubator.Incubator;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 状态重放管理器（PLAN-014 Phase 1 / PLAN-012 A-2 核心）。
 *
 * <p>职责：把"区块交易重放 → 账户/孵化状态持久化 → 状态根重建校验"
 * 收敛到单一入口，供确认路径 {@link StatetreeUpdate} 调用。</p>
 *
 * <p>现状与边界（诚实记录）：</p>
 * <ul>
 *   <li>重放引擎复用 {@link MerkleRule#validateMerkle}（与出块方
 *       {@code Miner} 生成 {@code hashMerkleState} 用的是同一套执行逻辑）；</li>
 *   <li>持久化语义与旧 {@code StatetreeUpdate} 完全一致：
 *       {@code accountDB.insertAccountList} / {@code incubatorDB.insertIncubatorList}；</li>
 *   <li><b>Phase 2 处置接线</b>：重放后状态根不一致时发布
 *       {@link StateRootMismatchEvent}，由 {@code StateRootMismatchHandler}
 *       按 {@code nexus.core.state-root-mismatch.action} 处置
 *       （observe=仅日志/计数，默认；rollback=回滚 + 拉黑对端）；
 *       本类不阻断确认、不抛校验失败异常。</li>
 *   <li>{@code Account.getId() = pubkeyHash + blockHeight}，因此现有状态根
 *       承诺的是"本区块触碰了哪些账户、在什么高度"，不含余额/Nonce 明细；
 *       Phase 2 评估校验语义时需以此为基线。</li>
 * </ul>
 */
@Component
public class StateReplayManager {

    private static final Logger log = LoggerFactory.getLogger(StateReplayManager.class);

    @Autowired
    private MerkleRule merkleRule;

    @Autowired
    private AccountDB accountDB;

    @Autowired
    private IncubatorDB incubatorDB;

    @Autowired
    private ApplicationContext ctx;

    private final AtomicLong replayedBlocks = new AtomicLong();

    private final AtomicLong stateRootMismatches = new AtomicLong();

    /**
     * 重放一个已确认区块的交易并持久化状态，随后做状态根校验。
     *
     * @return 重放摘要（含校验结果）；校验失败不抛异常（Phase 1 观察位）
     * @throws InvalidProtocolBufferException 交易负载解析失败
     * @throws DecoderException               Hex 解码失败
     */
    public ReplayResult replayBlock(Block block) throws InvalidProtocolBufferException, DecoderException {
        Map<String, Object> merklemap = merkleRule.validateMerkle(block.body, block.nHeight);
        @SuppressWarnings("unchecked")
        List<Account> accountList = (List<Account>) merklemap.get("account");
        @SuppressWarnings("unchecked")
        List<Incubator> incubatorList = (List<Incubator>) merklemap.get("incubator");

        List<Object[]> accountRows = new ArrayList<>(accountList.size());
        for (Account account : accountList) {
            accountRows.add(new Object[]{
                    account.getId(), account.getBlockHeight(), account.getPubkeyHash(), account.getNonce()
                    , account.getBalance(), account.getIncubatecost(), account.getMortgage(), account.getVote()
            });
        }
        List<Object[]> incubatorRows = new ArrayList<>(incubatorList.size());
        for (Incubator incubator : incubatorList) {
            incubatorRows.add(new Object[]{
                    incubator.getId(), incubator.getShare_pubkeyhash()
                    , incubator.getPubkeyhash(), incubator.getTxid_issue()
                    , incubator.getHeight(), incubator.getCost(), incubator.getInterest_amount()
                    , incubator.getShare_amount(), incubator.getLast_blockheight_interest(), incubator.getLast_blockheight_share()
            });
        }

        accountDB.insertAccountList(accountRows);
        incubatorDB.insertIncubatorList(incubatorRows);

        Result verify = merkleRule.verifyStateRoots(block, accountList, incubatorList);
        replayedBlocks.incrementAndGet();

        if (!verify.isSuccess()) {
            stateRootMismatches.incrementAndGet();
            log.error("state replay mismatch (PLAN-014 Phase 2, see StateRootMismatchHandler): height={}, block={}, "
                            + "txs={}, replayedAccounts={}, replayedIncubators={}, reason={}",
                    block.nHeight, block.getHashHexString(),
                    block.body == null ? 0 : block.body.size(),
                    accountList.size(), incubatorList.size(), verify.getMessage());
            if (ctx != null) {
                ctx.publishEvent(new StateRootMismatchEvent(this, block, verify.getMessage()));
            }
        } else {
            log.debug("state replay verified: height={}, txs={}, accounts={}, incubators={}",
                    block.nHeight, block.body == null ? 0 : block.body.size(),
                    accountList.size(), incubatorList.size());
        }

        return new ReplayResult(block.nHeight,
                block.body == null ? 0 : block.body.size(),
                accountList.size(), incubatorList.size(),
                verify.isSuccess(), verify.getMessage());
    }

    public long getReplayedBlocks() {
        return replayedBlocks.get();
    }

    public long getStateRootMismatches() {
        return stateRootMismatches.get();
    }

    /**
     * 单区块重放摘要。
     */
    public static final class ReplayResult {

        private final long height;
        private final int txCount;
        private final int accountCount;
        private final int incubatorCount;
        private final boolean verified;
        private final String verifyMessage;

        public ReplayResult(long height, int txCount, int accountCount, int incubatorCount,
                            boolean verified, String verifyMessage) {
            this.height = height;
            this.txCount = txCount;
            this.accountCount = accountCount;
            this.incubatorCount = incubatorCount;
            this.verified = verified;
            this.verifyMessage = verifyMessage;
        }

        public long getHeight() {
            return height;
        }

        public int getTxCount() {
            return txCount;
        }

        public int getAccountCount() {
            return accountCount;
        }

        public int getIncubatorCount() {
            return incubatorCount;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getVerifyMessage() {
            return verifyMessage;
        }
    }
}
