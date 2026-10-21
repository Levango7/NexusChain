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

package org.nexus.core.account;

/**
 * 账户存储不可用。
 *
 * <p>用于区分“账户不存在”和“数据库查询失败”。前者是正常业务语义（返回
 * {@code null} 或 {@code Optional.empty()}），后者必须 fail-closed，不能让
 * 调用方把数据库故障误判成新账户并继续构造/验证区块。</p>
 */
public class AccountStoreUnavailableException extends IllegalStateException {

    public AccountStoreUnavailableException(String message) {
        super(message);
    }

    public AccountStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
