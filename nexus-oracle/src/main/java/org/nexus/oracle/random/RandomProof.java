package org.nexus.oracle.random;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 可验证随机数证明实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RandomProof implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 产出的随机数（十六进制或十进制字符串） */
    @JsonProperty("random")
    private String random;

    /** 伴随证明（VRF proof 字节流的编码） */
    @JsonProperty("proof")
    private String proof;

    /** 生成者签名（用于校验生成者身份） */
    @JsonProperty("signature")
    private String signature;

    /** 生成者公钥标识 */
    @JsonProperty("generator")
    private String generator;

    /** 输入种子 */
    @JsonProperty("seed")
    private String seed;
}