package org.nexus.compliance.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 可验证凭证（Verifiable Credential）实体。
 * <p>
 * 符合 W3C VC 规范的最小化凭证结构。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerifiableCredential {

    /** 发行者 */
    @JsonProperty("issuer")
    private String issuer;

    /** 持有者 */
    @JsonProperty("holder")
    private String holder;

    /** 凭证内容 */
    @JsonProperty("content")
    private String content;

    /** 签名 */
    @JsonProperty("signature")
    private String signature;

    /** 有效期（截止时间） */
    @JsonProperty("expirationDate")
    private Instant expirationDate;

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getHolder() { return holder; }
    public void setHolder(String holder) { this.holder = holder; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public Instant getExpirationDate() { return expirationDate; }
    public void setExpirationDate(Instant expirationDate) { this.expirationDate = expirationDate; }
}