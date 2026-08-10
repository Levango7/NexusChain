package org.nexus.core.event;

import org.nexus.core.Block;
import org.springframework.context.ApplicationEvent;

/**
 * Published when the state-tree update for a block FAILS (DB write error).
 *
 * <p>StateDB listens for this event to release the pending-block lock so the
 * node does not deadlock; the failed block is logged for operator intervention.</p>
 */
public class AccountUpdateFailedEvent extends ApplicationEvent {

    private final Block block;
    private final String reason;

    public AccountUpdateFailedEvent(Object source, Block block, String reason) {
        super(source);
        this.block = block;
        this.reason = reason;
    }

    public Block getBlock() { return block; }
    public String getReason() { return reason; }
}
