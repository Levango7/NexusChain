package org.nexus.signing.mpc.cggmp;

/**
 * CGGMP21 验签结果 DTO（G 批）。
 *
 * <p>区别于 {@code success}：</p>
 * <ul>
 *   <li>{@code success} — RPC 调用是否成功（参数合法、状态机可达）</li>
 *   <li>{@code valid}   — 验签逻辑结论（聚合公钥可恢复该签名）</li>
 * </ul>
 */
public final class CgVerifyResult {

    private final boolean valid;
    private final boolean success;
    private final String error;

    public CgVerifyResult(boolean valid, boolean success, String error) {
        this.valid = valid;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    public boolean isValid() { return valid; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
}
