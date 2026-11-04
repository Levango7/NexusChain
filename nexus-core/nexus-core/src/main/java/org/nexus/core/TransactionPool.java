/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.core;

import org.nexus.core.account.Transaction;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class TransactionPool {
    public static class TransactionComparator implements Comparator<Transaction> {
        @Override
        public int compare(Transaction o1, Transaction o2) {
            // B-12 修复：使用 Long.compare 替代 (int)(long - long) 强转，
            // 避免高并发下 fee 差值超过 Integer.MAX_VALUE 时的 int 溢出导致排序错乱。
            return Long.compare(o2.getFee(), o1.getFee());
        }
    }


    private ReadWriteLock lock;

    private PriorityQueue<Transaction> pq;
    private Set<String> indices;

    public TransactionPool() {
        this.pq = new PriorityQueue<>(1000, new TransactionComparator());
        // B-13 修复：使用 ConcurrentHashMap.newKeySet() 替代 HashSet，
        // 保证 has() 等无锁读操作在并发访问下的线程安全。
        this.indices = ConcurrentHashMap.newKeySet();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * 交易入池守卫并提交。
     *
     * <p>拒绝永远不可能通过区块校验的交易，避免调用方在交易不可能上链时仍以成功语义返回：</p>
     * <ul>
     *   <li>全零 {@code from} 或空/全零签名（{@code SignatureRule} 必然验签失败）；</li>
     *   <li>0x10–0x1a 支付扩展类型（{@code TransactionCheck} 的 {@code type <= 15} 门禁必然拒绝）。</li>
     * </ul>
     * <p>COINBASE 为共识层生成的系统交易，无 from/签名，白名单放行。</p>
     *
     * @return 全部交易均被接受（或已存在于池中）时返回 true；任一交易被守卫拒绝时返回 false
     */
    public boolean add(Transaction... txs) {
        lock.writeLock().lock();
        try {
            if (txs == null) {
                return false;
            }
            boolean allAccepted = true;
            for (Transaction tx : txs) {
                if (!isAdmissible(tx)) {
                    allAccepted = false;
                    continue;
                }
                if(indices.contains(tx.getHashHexString())){
                    continue;
                }
                pq.add(tx);
                indices.add(tx.getHashHexString());
            }
            return allAccepted;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 入池前静态守卫：只做与链上校验等价的快速判定，不验签。
     */
    private static boolean isAdmissible(Transaction tx) {
        if (tx == null) {
            return false;
        }
        // coinbase 由共识层生成，天然没有 from / 签名
        if (tx.type == Transaction.Type.COINBASE.ordinal()) {
            return true;
        }
        // 支付扩展类型当前无法通过 TransactionCheck 的类型门禁，入池只会成为死账
        if (tx.isPaymentExtensionType()) {
            return false;
        }
        return !isAllZero(tx.from) && !isAllZero(tx.signature);
    }

    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean has(String txHash){
        return indices.contains(txHash);
    }

    public Transaction poll() {
        if (pq.size() == 0){
            return null;
        }
        lock.writeLock().lock();
        try {
            Transaction tx = pq.poll();
            indices.remove(tx.getHashHexString());
            return tx;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return pq.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Transaction> getAll(){
        lock.readLock().lock();
        try {
            return Arrays.asList(pq.toArray(new Transaction[]{}));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取交易池中指定类型的所有交易。
     * <p>用于查询特定类型（如支付通道、批量转账、稳定币、跨链桥等）的交易，
     * 便于支付扩展模块按类型筛选待处理交易。</p>
     *
     * @param type 交易类型 ordinal（对应 {@link Transaction.Type} 的序号）
     * @return 匹配类型的交易列表，如果池为空则返回空列表
     */
    public List<Transaction> getTransactionsByType(int type){
        lock.readLock().lock();
        try {
            List<Transaction> result = new ArrayList<>();
            for (Transaction tx : pq) {
                if (tx.type == type) {
                    result.add(tx);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void main(String[] args) {
        Transaction t1 = new Transaction();
        t1.type = Transaction.Type.DEPOSIT.ordinal();
        t1.gasPrice = 1000;
        Transaction t2 = new Transaction();
        t2.type = Transaction.Type.DEPOSIT.ordinal();
        t2.gasPrice = 2000;
        TransactionPool pol = new TransactionPool();
        pol.add(t1, t2);
        System.out.println(pol.poll().getFee());
        System.out.println(pol.has(t1.getHashHexString()));
        System.out.println(pol.poll().getFee());
        System.out.println(pol.size());
    }
}