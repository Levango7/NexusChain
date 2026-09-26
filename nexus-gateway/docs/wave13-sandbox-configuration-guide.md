# Wave 13: 渠道沙箱对接配置指南

> **版本**: 1.0
> **日期**: 2026-09-27
> **适用范围**: NexusChain 渠道沙箱对接（Wave 13）

---

## 1. 概述

本文档指导如何将微信支付和支付宝连接器从 dry-run 模拟模式切换为沙箱环境真实对接。

### 1.1 前置条件
- NexusChain 已部署并正常运行
- 已完成 Wave 13 代码修改
- 已获取微信支付/支付宝沙箱账号

### 1.2 模式说明
- **dry-run 模式**（默认）：不发起真实 API 调用，返回模拟成功响应
- **沙箱模式**：发起真实 API 调用到沙箱环境，验证签名/下单/查询/退款/回调全流程
- **生产模式**：发起真实 API 调用到生产环境（本文档不涉及）

---

## 2. 微信支付沙箱配置

### 2.1 沙箱注册步骤

1. 访问微信支付商户平台沙箱：https://pay.weixin.qq.com/sandbox
2. 注册沙箱商户账号，获取以下信息：
   - 商户号（mch-id）
   - 应用ID（app-id）
   - APIv3 密钥（32字节字符串）
   - 商户 API 证书（包含私钥和证书序列号）
3. 下载商户 API 证书，提取：
   - 商户私钥（PKCS#8 Base64 编码）
   - 商户证书序列号（cert-serial-no）
4. 获取微信平台证书（系统自动通过 GET /v3/certificates 获取）

### 2.2 application.yml 配置示例

```yaml
nexus:
  connectors:
    wechat:
      enabled: true
      sandbox: false  # false = 真实 API 调用（沙箱环境）
      app-id: "wxXXXXXXXXXXXXXXXX"  # 沙箱应用ID
      mch-id: "XXXXXXXXXX"  # 沙箱商户号
      api-v3-key: "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"  # 32字节 APIv3 密钥
      merchant-private-key: "MIIEvQIBADANB..."  # PKCS#8 Base64 商户私钥
      cert-serial-no: "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"  # 商户证书序列号
      api-base-url: "https://api.mch.weixin.qq.com"  # 微信支付 API 地址（沙箱与生产相同）
```

### 2.3 配置项说明

| 配置项 | 必填 | 说明 |
|--------|------|------|
| `enabled` | 是 | 是否启用微信支付连接器 |
| `sandbox` | 是 | true=dry-run 模拟，false=真实 API 调用 |
| `app-id` | 是（sandbox=false） | 微信应用 ID |
| `mch-id` | 是（sandbox=false） | 微信商户号 |
| `api-v3-key` | 是（sandbox=false） | APIv3 密钥（32字节），用于 AES-256-GCM 解密 |
| `merchant-private-key` | 是（sandbox=false） | 商户 RSA 私钥（PKCS#8 Base64） |
| `cert-serial-no` | 是（sandbox=false） | 商户证书序列号 |
| `api-base-url` | 否 | API 基础地址，默认为生产地址 |

### 2.4 关键修正说明

Wave 13 对微信支付连接器做了以下关键修正：
1. **签名算法**：从 HMAC-SHA256 升级为 RSA-SHA256（商户私钥签名）
2. **回调验签**：从 HMAC-SHA256 升级为 RSA-SHA256（微信平台证书公钥验签）
3. **回调解密**：新增 AES-256-GCM 解密 resource.ciphertext
4. **平台证书**：新增自动获取和缓存机制（GET /v3/certificates）

---

## 3. 支付宝沙箱配置

### 3.1 沙箱注册步骤

1. 访问支付宝开放平台沙箱：https://open.alipay.com/develop/sandbox
2. 注册沙箱应用，获取以下信息：
   - 应用ID（app-id）
   - 商户私钥（RSA2）
   - 支付宝公钥
3. 沙箱网关地址：https://openapi-sandbox.dl.alipaydev.com/gateway.do

### 3.2 application.yml 配置示例

```yaml
nexus:
  connectors:
    alipay:
      enabled: true
      sandbox: false  # false = 真实 API 调用（沙箱环境）
      app-id: "XXXXXXXXXXXX"  # 沙箱应用ID
      merchant-private-key: "MIIEvQIBADANB..."  # RSA2 商户私钥
      alipay-public-key: "MIIBIjANBg..."  # 支付宝公钥
      api-base-url: "https://openapi-sandbox.dl.alipaydev.com/gateway.do"  # 沙箱网关
```

### 3.3 配置项说明

| 配置项 | 必填 | 说明 |
|--------|------|------|
| `enabled` | 是 | 是否启用支付宝连接器 |
| `sandbox` | 是 | true=dry-run 模拟，false=真实 API 调用 |
| `app-id` | 是（sandbox=false） | 支付宝应用 ID |
| `merchant-private-key` | 是（sandbox=false） | RSA2 商户私钥 |
| `alipay-public-key` | 是（sandbox=false） | 支付宝公钥（用于回调验签） |
| `api-base-url` | 否 | API 网关地址，默认为沙箱地址 |

### 3.4 关键修正说明

Wave 13 对支付宝连接器做了以下关键修正：
1. **bizContent 序列化**：从 `Map.toString()` 改为 `ObjectMapper.writeValueAsString()`
2. **金额格式**：`total_amount` 从分转换为元格式字符串（如 "0.01"）
3. **默认网关**：从生产地址改为沙箱地址

---

## 4. 切换模式

### 4.1 从 dry-run 切换到沙箱模式

1. 在 application.yml 中设置 `sandbox: false`
2. 填写所有必填配置项（密钥、证书等）
3. 重启 NexusChain 服务
4. 验证健康检查端点返回 up 状态

### 4.2 从沙箱切换回 dry-run 模式

1. 在 application.yml 中设置 `sandbox: true`
2. 重启 NexusChain 服务
3. 连接器将返回模拟成功响应，不发起真实 API 调用

### 4.3 配置缺失保护

当 `sandbox: false` 但密钥未配置时，连接器自动回退到 dry-run 模式，不会使用空密钥发起真实 API 调用。

---

## 5. 常见问题排查

### 5.1 微信支付签名失败

**症状**：微信 API 返回 `SIGN_ERROR` 或签名验证失败

**排查步骤**：
1. 检查 `merchant-private-key` 是否为完整的 PKCS#8 Base64 编码
2. 检查 `cert-serial-no` 是否与商户证书序列号一致
3. 检查 `api-v3-key` 是否为 32 字节字符串
4. 查看日志中的签名算法和签名前16字符

### 5.2 微信回调验签失败

**症状**：微信回调返回 `{"code":"FAIL"}`，日志显示"验签失败"

**排查步骤**：
1. 检查微信平台证书是否已获取（查看 `wechat_platform_certificates` 表）
2. 检查 `Wechatpay-Serial` 头是否与平台证书序列号匹配
3. 检查 `api-v3-key` 是否正确（解密失败也会导致回调处理失败）

### 5.3 支付宝 bizContent 格式错误

**症状**：支付宝 API 返回参数格式错误

**排查步骤**：
1. 确认 Wave 13 代码修改已部署（`ObjectMapper.writeValueAsString()` 替代 `Map.toString()`）
2. 检查 `total_amount` 是否为字符串类型（如 "0.01"）
3. 查看日志中的 bizContent 内容

### 5.4 支付宝沙箱网关不可达

**症状**：支付宝 API 调用超时或连接失败

**排查步骤**：
1. 检查 `api-base-url` 是否为沙箱地址
2. 确认网络可访问 `openapi-sandbox.dl.alipaydev.com`
3. 检查 RestTemplate 超时配置（应设置为 10 秒）

---

## 6. 数据库迁移

Wave 13 新增 V71 Flyway 迁移，创建 `wechat_platform_certificates` 表用于缓存微信平台证书。

迁移脚本路径：`src/main/resources/db/migration/V71__wechat_platform_certificates.sql`

迁移会在服务启动时自动执行，无需手动操作。