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

package org.nexus.keystore.util;

import org.springframework.cache.annotation.Cacheable;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.UUID;

/**
 * Convenience wrapper for working with UUIDs in Base58. Since the Base58 operations are fairly expensive computationally, this
 * class is annotated with Spring's {@link Cacheable} using the cache named {@literal base58uuid}. This annotation is contained in
 * the {@literal spring-context} artifact, and in its absence at runtime, the classloader will simply ignore it.
 *
 * @author Christopher Smith
 *
 */
public class Base58UUID {

    /**
     * UUID → Base58 编码。
     *
     * <p>P1（2026-09-17 审查修正）：{@code @Cacheable} 原被标注在**类**上。
     * Spring 的 {@code @Cacheable} 是<b>方法级</b>注解，标在类上不产生任何效果
     * （类级共享缓存配置应使用 {@code @CacheConfig}）—— 即该缓存从未生效过。
     * 现移到方法上，使注解语义正确。</p>
     *
     * <p>⚠️ 注意：即便位置正确，本缓存**当前仍不生效**，原因有二：</p>
     * <ol>
     *   <li>全仓无 {@code @EnableCaching}，Spring 未创建缓存基础设施；</li>
     *   <li>本类在全仓<b>无任何调用方</b>（引入的库文件），且调用方若以
     *       {@code new Base58UUID()} 使用也不会经过代理。</li>
     * </ol>
     * <p>因此该注解目前仅表达「此方法可安全缓存」的意图（方法为纯函数，
     * 同输入必得同输出），不构成实际缓存。若后续启用缓存，无需改动本类。</p>
     */
    @Cacheable("base58uuid")
    public String encode(UUID uuid) {
        // 50-50 chance that the UUID's high {@code long} value will be negative, so just preemptively
        // pad the byte buffer we'll be encoding from
        ByteBuffer bb = ByteBuffer.allocate(17);
        bb.put((byte) 0);
        bb.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).flip();
        return Base58Encoder.doEncode(bb.array());
    }

    public UUID decode(String base58) {
        ByteBuffer bb = ByteBuffer.wrap(Base58Encoder.doDecode(base58, 16));
        return new UUID(bb.getLong(), bb.getLong());
    }

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Convenience wrapper for converting a {@code String} to a name-based UUID and returning the Base58-encoded value. The
     * {@code String}'s characters are converted to bytes according to UTF-8.
     *
     * @param name the name from which to construct a UUID
     * @return the name converted to a UUID and Base58-encoded
     */
    public String encodeUuidFromName(String name) {
        return encode(UUID.nameUUIDFromBytes(name.getBytes(UTF_8)));
    }
}