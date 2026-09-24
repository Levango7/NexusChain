-- 商户入驻申请表
-- 记录商户从提交入驻申请到审核完成的完整生命周期：
-- PENDING → REVIEWING → APPROVED / REJECTED / SUSPENDED
CREATE TABLE merchant_applications (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_name         VARCHAR(128) NOT NULL COMMENT '商户名称（公司全称）',
    contact_name          VARCHAR(64)  NOT NULL COMMENT '联系人姓名',
    contact_email         VARCHAR(128) NOT NULL COMMENT '联系人邮箱',
    contact_phone         VARCHAR(32)  NOT NULL COMMENT '联系人电话',
    business_type         VARCHAR(32)  NOT NULL COMMENT '业务类型: ECOMMERCE/DIGITAL_SERVICES/PHYSICAL_RETAIL/SERVICES/OTHER',
    business_license_no   VARCHAR(64)  NOT NULL COMMENT '营业执照编号（18位统一社会信用代码）',
    business_license_url  VARCHAR(512) COMMENT '营业执照文件 URL',
    website_url           VARCHAR(512) COMMENT '商户网站 URL',
    description           VARCHAR(1024) COMMENT '商户描述（业务简介）',
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '申请状态: PENDING/REVIEWING/APPROVED/REJECTED/SUSPENDED',
    submitted_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '申请提交时间',
    reviewed_at           TIMESTAMP(6) NULL DEFAULT NULL COMMENT '审核开始时间',
    reviewer_id           VARCHAR(64)  COMMENT '审核人员 ID',
    review_comment        VARCHAR(1024) COMMENT '审核意见（通过/拒绝原因）',
    approved_at           TIMESTAMP(6) NULL DEFAULT NULL COMMENT '审核通过时间',
    merchant_id           BIGINT       COMMENT '关联商户 ID（审核通过后创建）',
    PRIMARY KEY (id),
    INDEX idx_ma_status (status),
    UNIQUE KEY uk_ma_business_license (business_license_no),
    INDEX idx_ma_merchant_id (merchant_id),
    INDEX idx_ma_contact_email (contact_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户入驻申请表';