"use strict";

/**
 * ConPay RPC 客户端模块
 * 项目: ConPay
 * 代币符号: CPAY
 *
 * 本模块提供与 ConPay 节点 RPC 接口的交互能力，
 * 将 SDK 构造并签名的交易广播到区块链网络，
 * 同时支持查询 Nonce、余额、区块高度、交易确认状态等信息。
 *
 * 所有方法均返回 Promise，可在 async/await 流程中使用。
 */

/**
 * ConPayRpcClient - ConPay 节点 RPC 客户端
 *
 * 用法:
 *   const ConPayRpcClient = require('./rpc-client');
 *   const client = new ConPayRpcClient('http://localhost:19585');
 *   const balance = await client.sendBalance(pubkeyhash);
 */
class ConPayRpcClient {

    /**
     * 构造函数
     * @param {string} nodeUrl - 节点 RPC 地址，例如 'http://localhost:19585'
     */
    constructor(nodeUrl) {
        if (!nodeUrl) {
            throw new Error('ConPayRpcClient: nodeUrl 不能为空');
        }
        // 去除末尾斜杠，避免拼接时出现双斜杠
        this.nodeUrl = nodeUrl.replace(/\/+$/, '');
        // 默认请求超时（毫秒）
        this.timeout = 30000;
    }

    // ========== 内部 HTTP 工具方法 ==========

    /**
     * 发送 HTTP POST 请求
     * @param {string} path - 接口路径，例如 '/channel/open'
     * @param {object} body - 请求体 JSON 对象
     * @returns {Promise<object>} - 返回解析后的 JSON 响应
     * @private
     */
    async _post(path, body) {
        const url = this.nodeUrl + path;
        const options = {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json; charset=UTF-8',
                'Accept': 'application/json'
            },
            // 请求体序列化为 JSON 字符串
            body: JSON.stringify(body || {})
        };

        // 使用 Node.js 内置的 http/https 模块或全局 fetch
        if (typeof fetch === 'function') {
            // Node.js 18+ 或浏览器环境支持全局 fetch
            const controller = (typeof AbortController === 'function') ? new AbortController() : null;
            if (controller) {
                options.signal = controller.signal;
                // 设置超时定时器
                const timer = setTimeout(() => controller.abort(), this.timeout);
                try {
                    const response = await fetch(url, options);
                    clearTimeout(timer);
                    return await this._parseResponse(response);
                } catch (err) {
                    clearTimeout(timer);
                    throw new Error('ConPayRpcClient POST 请求失败 [' + url + ']: ' + err.message);
                }
            } else {
                // 没有 AbortController 的环境，直接请求
                const response = await fetch(url, options);
                return await this._parseResponse(response);
            }
        } else {
            // 回退到 XMLHttpRequest（浏览器旧环境）
            return this._postXHR(url, body);
        }
    }

    /**
     * 发送 HTTP GET 请求
     * @param {string} path - 接口路径，例如 '/channel/state/abc123'
     * @returns {Promise<object>} - 返回解析后的 JSON 响应
     * @private
     */
    async _get(path) {
        const url = this.nodeUrl + path;
        const options = {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        };

        if (typeof fetch === 'function') {
            const controller = (typeof AbortController === 'function') ? new AbortController() : null;
            if (controller) {
                options.signal = controller.signal;
                const timer = setTimeout(() => controller.abort(), this.timeout);
                try {
                    const response = await fetch(url, options);
                    clearTimeout(timer);
                    return await this._parseResponse(response);
                } catch (err) {
                    clearTimeout(timer);
                    throw new Error('ConPayRpcClient GET 请求失败 [' + url + ']: ' + err.message);
                }
            } else {
                const response = await fetch(url, options);
                return await this._parseResponse(response);
            }
        } else {
            // 回退到 XMLHttpRequest（浏览器旧环境）
            return this._getXHR(url);
        }
    }

    /**
     * 解析 fetch 响应
     * @param {Response} response - fetch 返回的 Response 对象
     * @returns {Promise<object>}
     * @private
     */
    async _parseResponse(response) {
        if (!response.ok) {
            let errorMsg = 'HTTP ' + response.status + ' ' + response.statusText;
            try {
                const errorBody = await response.text();
                if (errorBody) {
                    errorMsg += ': ' + errorBody;
                }
            } catch (e) {
                // 忽略响应体读取失败
            }
            throw new Error(errorMsg);
        }
        const text = await response.text();
        if (!text) {
            return {};
        }
        try {
            return JSON.parse(text);
        } catch (e) {
            // 非 JSON 响应，直接返回原始文本
            return { raw: text };
        }
    }

    /**
     * 使用 XMLHttpRequest 发送 POST 请求（浏览器旧环境回退方案）
     * @param {string} url - 完整请求地址
     * @param {object} body - 请求体
     * @returns {Promise<object>}
     * @private
     */
    _postXHR(url, body) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', url, true);
            xhr.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
            xhr.setRequestHeader('Accept', 'application/json');
            xhr.timeout = this.timeout;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try {
                            resolve(JSON.parse(xhr.responseText));
                        } catch (e) {
                            resolve({ raw: xhr.responseText });
                        }
                    } else {
                        reject(new Error('HTTP ' + xhr.status + ': ' + xhr.responseText));
                    }
                }
            };
            xhr.onerror = function() {
                reject(new Error('ConPayRpcClient XHR POST 请求失败: ' + url));
            };
            xhr.ontimeout = function() {
                reject(new Error('ConPayRpcClient XHR POST 请求超时: ' + url));
            };
            xhr.send(JSON.stringify(body || {}));
        });
    }

    /**
     * 使用 XMLHttpRequest 发送 GET 请求（浏览器旧环境回退方案）
     * @param {string} url - 完整请求地址
     * @returns {Promise<object>}
     * @private
     */
    _getXHR(url) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('GET', url, true);
            xhr.setRequestHeader('Accept', 'application/json');
            xhr.timeout = this.timeout;
            xhr.onreadystatechange = function() {
                if (xhr.readyState === 4) {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try {
                            resolve(JSON.parse(xhr.responseText));
                        } catch (e) {
                            resolve({ raw: xhr.responseText });
                        }
                    } else {
                        reject(new Error('HTTP ' + xhr.status + ': ' + xhr.responseText));
                    }
                }
            };
            xhr.onerror = function() {
                reject(new Error('ConPayRpcClient XHR GET 请求失败: ' + url));
            };
            xhr.ontimeout = function() {
                reject(new Error('ConPayRpcClient XHR GET 请求超时: ' + url));
            };
            xhr.send();
        });
    }

    // ========== 基础链查询接口 ==========

    /**
     * 查询账户 Nonce
     * @param {string} pubkeyhash - 签发者公钥哈希（十六进制字符串）
     * @returns {Promise<object>} - 包含 nonce 信息的响应
     */
    async sendNonce(pubkeyhash) {
        // POST /transaction/nonce，请求体携带 pubkeyhash
        return await this._post('/transaction/nonce', { pubkeyhash: pubkeyhash });
    }

    /**
     * 查询账户余额
     * @param {string} pubkeyhash - 签发者公钥哈希（十六进制字符串）
     * @returns {Promise<object>} - 包含余额信息的响应
     */
    async sendBalance(pubkeyhash) {
        // POST /transaction/balance，请求体携带 pubkeyhash
        return await this._post('/transaction/balance', { pubkeyhash: pubkeyhash });
    }

    /**
     * 广播已签名的交易到节点
     * @param {string} traninfo - 已签名交易的十六进制字符串
     * @returns {Promise<object>} - 包含交易哈希等信息的响应
     */
    async sendTransaction(traninfo) {
        // POST /transaction/send，请求体携带交易数据
        return await this._post('/transaction/send', { traninfo: traninfo });
    }

    /**
     * 查询当前区块高度
     * @returns {Promise<object>} - 包含区块高度的响应
     */
    async getHeight() {
        // GET /block/height
        return await this._get('/block/height');
    }

    /**
     * 查询交易是否已被确认
     * @param {string} txHash - 交易哈希（十六进制字符串）
     * @returns {Promise<object>} - 包含确认状态的响应
     */
    async transactionConfirmed(txHash) {
        // GET /transaction/confirmed/{txHash}
        return await this._get('/transaction/confirmed/' + encodeURIComponent(txHash));
    }

    /**
     * 查询交易详情
     * @param {string} txHash - 交易哈希（十六进制字符串）
     * @returns {Promise<object>} - 包含交易详情的响应
     */
    async getTransaction(txHash) {
        // GET /transaction/{txHash}
        return await this._get('/transaction/' + encodeURIComponent(txHash));
    }

    // ========== 支付通道接口 ==========

    /**
     * 开启支付通道
     * @param {string} from - 签发者公钥哈希（十六进制）
     * @param {string} to - 对手方公钥哈希（十六进制）
     * @param {string} amount - 通道资金金额（字符串形式）
     * @param {number} lockTime - 通道锁定区块数
     * @returns {Promise<object>} - 节点响应
     */
    async openChannel(from, to, amount, lockTime) {
        // POST /channel/open
        return await this._post('/channel/open', {
            from: from,
            to: to,
            amount: amount,
            lockTime: lockTime
        });
    }

    /**
     * 关闭支付通道
     * @param {string} channelId - 通道 ID
     * @param {string} finalBalance1 - 甲方最终余额
     * @param {string} finalBalance2 - 乙方最终余额
     * @param {number} nonce - 当前 Nonce
     * @returns {Promise<object>} - 节点响应
     */
    async closeChannel(channelId, finalBalance1, finalBalance2, nonce) {
        // POST /channel/close
        return await this._post('/channel/close', {
            channelId: channelId,
            finalBalance1: finalBalance1,
            finalBalance2: finalBalance2,
            nonce: nonce
        });
    }

    /**
     * 查询支付通道状态
     * @param {string} channelId - 通道 ID
     * @returns {Promise<object>} - 包含通道状态的响应
     */
    async getChannelState(channelId) {
        // GET /channel/state/{channelId}
        return await this._get('/channel/state/' + encodeURIComponent(channelId));
    }

    /**
     * 列出指定地址关联的支付通道
     * @param {string} address - 地址（Base58 编码）
     * @returns {Promise<object>} - 包含通道列表的响应
     */
    async listChannels(address) {
        // GET /channel/list/{address}
        return await this._get('/channel/list/' + encodeURIComponent(address));
    }

    // ========== 批量转账接口 ==========

    /**
     * 批量转账
     * @param {string} fromPubkey - 签发者公钥哈希（十六进制）
     * @param {Array} recipients - 收款方数组，每个元素包含 {address, amount}
     * @param {string} prikey - 私钥（十六进制）
     * @param {number} nonce - 当前 Nonce
     * @returns {Promise<object>} - 节点响应
     */
    async batchTransfer(fromPubkey, recipients, prikey, nonce) {
        // POST /batch/transfer
        return await this._post('/batch/transfer', {
            fromPubkey: fromPubkey,
            recipients: recipients,
            prikey: prikey,
            nonce: nonce
        });
    }

    /**
     * 查询批量转账状态
     * @param {string} txHash - 批量转账交易哈希
     * @returns {Promise<object>} - 包含批量转账状态的响应
     */
    async getBatchStatus(txHash) {
        // GET /batch/status/{txHash}
        return await this._get('/batch/status/' + encodeURIComponent(txHash));
    }

    // ========== 稳定币接口 ==========

    /**
     * 铸造稳定币（抵押 CPAY 铸造稳定币）
     * @param {string} fromPubkey - 签发者公钥哈希（十六进制）
     * @param {string} collateral - 抵押金额（字符串形式）
     * @param {string} mintAmount - 铸造金额（字符串形式）
     * @param {string} prikey - 私钥（十六进制）
     * @param {number} nonce - 当前 Nonce
     * @returns {Promise<object>} - 节点响应
     */
    async mintStableCoin(fromPubkey, collateral, mintAmount, prikey, nonce) {
        // POST /stablecoin/mint
        return await this._post('/stablecoin/mint', {
            fromPubkey: fromPubkey,
            collateral: collateral,
            mintAmount: mintAmount,
            prikey: prikey,
            nonce: nonce
        });
    }

    /**
     * 赎回稳定币（用稳定币赎回抵押的 CPAY）
     * @param {string} fromPubkey - 签发者公钥哈希（十六进制）
     * @param {string} redeemAmount - 赎回金额（字符串形式）
     * @param {string} prikey - 私钥（十六进制）
     * @param {number} nonce - 当前 Nonce
     * @returns {Promise<object>} - 节点响应
     */
    async redeemStableCoin(fromPubkey, redeemAmount, prikey, nonce) {
        // POST /stablecoin/redeem
        return await this._post('/stablecoin/redeem', {
            fromPubkey: fromPubkey,
            redeemAmount: redeemAmount,
            prikey: prikey,
            nonce: nonce
        });
    }

    /**
     * 查询抵押率
     * @param {string} address - 地址（Base58 编码或公钥哈希）
     * @returns {Promise<object>} - 包含抵押率的响应
     */
    async getCollateralRatio(address) {
        // GET /stablecoin/collateral/{address}
        return await this._get('/stablecoin/collateral/' + encodeURIComponent(address));
    }

    /**
     * 查询稳定币当前价格
     * @returns {Promise<object>} - 包含价格的响应
     */
    async getStableCoinPrice() {
        // GET /stablecoin/price
        return await this._get('/stablecoin/price');
    }

    // ========== 跨链桥接口 ==========

    /**
     * 跨链锁定（将 CPAY 锁定并映射到目标链）
     * @param {string} fromPubkey - 签发者公钥哈希（十六进制）
     * @param {string} targetChain - 目标链标识
     * @param {string} recipient - 目标链上的接收地址
     * @param {string} amount - 锁定金额（字符串形式）
     * @param {string} prikey - 私钥（十六进制）
     * @param {number} nonce - 当前 Nonce
     * @returns {Promise<object>} - 节点响应
     */
    async bridgeLock(fromPubkey, targetChain, recipient, amount, prikey, nonce) {
        // POST /bridge/lock
        return await this._post('/bridge/lock', {
            fromPubkey: fromPubkey,
            targetChain: targetChain,
            recipient: recipient,
            amount: amount,
            prikey: prikey,
            nonce: nonce
        });
    }

    /**
     * 查询跨链桥交易状态
     * @param {string} txHash - 跨链交易哈希
     * @returns {Promise<object>} - 包含桥状态的响应
     */
    async bridgeStatus(txHash) {
        // GET /bridge/status/{txHash}
        return await this._get('/bridge/status/' + encodeURIComponent(txHash));
    }

    /**
     * 查询跨链桥限额
     * @returns {Promise<object>} - 包含桥限额信息的响应
     */
    async bridgeLimit() {
        // GET /bridge/limit
        return await this._get('/bridge/limit');
    }
}

// 导出 ConPayRpcClient 类
module.exports = ConPayRpcClient;
