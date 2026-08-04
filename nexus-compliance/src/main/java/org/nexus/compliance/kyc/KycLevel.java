package org.nexus.compliance.kyc;

/**
 * KYC 等级枚举。
 */
public enum KycLevel {

    /** 未认证 */
    NONE,

    /** 基础认证 */
    BASIC,

    /** 增强认证 */
    ENHANCED,

    /** 机构认证 */
    INSTITUTIONAL
}