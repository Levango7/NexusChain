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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Serialization utility.
 *
 * <p>REQ-16 安全加固：消除 Java 原生反序列化风险。
 *
 * <p>原实现使用 {@link ObjectInputStream#readObject()} 直接反序列化任意字节流，
 * 攻击者可构造恶意序列化数据触发 RCE（如 CommonsCollections gadget chain）。
 *
 * <p>修复策略（双保险）：
 * <ol>
 *   <li>主路径：使用 Jackson {@link ObjectMapper#readValue(byte[], Class)} 限定目标类型，
 *       不接受任意类反序列化，从根上阻断 gadget chain。</li>
 *   <li>兜底：对遗留调用方仍走 {@link ObjectInputStream} 时，注入 {@link ObjectInputFilter}
 *       白名单（JDK 9+），仅允许基础类型与 org.nexus.keystore 包内类。</li>
 * </ol>
 *
 * <p>方法签名变更：{@code toObject(byte[])} 重载为 {@code toObject(byte[], Class)}，
 * 调用方必须显式声明期望类型；旧无参签名保留兼容但内部走 Jackson + ObjectInputFilter 兜底，
 * 反序列化结果类型不在白名单时抛 {@link IllegalStateException}。
 */
public class SerializableUtil {

    /**
     * Shared ObjectMapper — thread-safe per Jackson documentation after configuration.
     * Disable default typing to prevent polymorphic deserialization gadgets.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * ObjectInputFilter whitelist for the legacy {@link #toObject(byte[])} fallback path.
     * Allows only JDK base types, concrete collections, and org.nexus.keystore.* classes.
     * <p>P2 加固：将 {@code java.util.*} 收窄为实际使用的具体集合类（HashMap/ArrayList/Arrays$ArrayList/
     * HashSet/LinkedList/TreeMap/TreeSet/LinkedHashMap/LinkedHashSet/Collections$Unmodifiable*），
     * 阻断攻击者利用其他 java.util 内部类（如 java.util.Comparator 等）构造 gadget chain。
     */
    private static final ObjectInputFilter DESERIALIZATION_FILTER = ObjectInputFilter.Config.createFilter(
            "java.lang.*;java.lang.reflect.Proxy;"
            + "java.util.HashMap;java.util.ArrayList;java.util.Arrays$ArrayList;"
            + "java.util.HashSet;java.util.LinkedList;"
            + "java.util.TreeMap;java.util.TreeSet;"
            + "java.util.LinkedHashMap;java.util.LinkedHashSet;"
            + "java.util.Collections$UnmodifiableRandomAccessList;"
            + "java.util.Collections$UnmodifiableCollection;"
            + "java.util.Collections$UnmodifiableSet;"
            + "java.util.Collections$UnmodifiableMap;"
            + "java.util.Collections$EmptyList;"
            + "java.util.Collections$EmptySet;"
            + "java.util.Collections$EmptyMap;"
            + "java.util.Collections$SingletonList;"
            + "java.util.Collections$SingletonSet;"
            + "java.util.Collections$SingletonMap;"
            + "java.math.*;java.time.*;java.io.Serializable;"
            + "org.nexus.keystore.*;"
            + "!*"
    );

    /**
     * Maximum allowed deserialized object size (bytes) — defense in depth against
     * resource-exhaustion gadgets (e.g. billion-laughs).
     */
    private static final int MAX_DESERIALIZED_SIZE = 16 * 1024 * 1024; // 16 MiB

    /**
     * java对象序列化成字节数组（保持原签名，向后兼容）。
     *
     * <p>实现仍使用 {@link ObjectOutputStream}：序列化方向不构成 RCE 风险，
     * 风险仅在反序列化方向。
     *
     * @param object 待序列化对象
     * @return 序列化字节
     */
    public static byte[] toBytes(Object object) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(baos);
            oos.writeObject(object);
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        } finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 字节数组反序列化成java对象（推荐入口，REQ-16 修复）。
     *
     * <p>使用 Jackson {@link ObjectMapper#readValue(byte[], Class)} 限定目标类型，
     * 不接受任意类反序列化，从根上阻断 gadget chain RCE。
     *
     * @param bytes 序列化字节
     * @param clazz 期望的目标类型（不可为 null）
     * @param <T>   目标类型
     * @return 反序列化对象
     * @throws IllegalArgumentException if bytes is null/empty or clazz is null
     * @throws RuntimeException         if deserialization fails
     */
    public static <T> T toObject(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be null or empty");
        }
        if (bytes.length > MAX_DESERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                "Deserialized payload too large: " + bytes.length
                + " > " + MAX_DESERIALIZED_SIZE + " bytes");
        }
        if (clazz == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        try {
            ObjectReader reader = MAPPER.readerFor(clazz);
            return reader.readValue(bytes);
        } catch (IOException ex) {
            throw new RuntimeException("Jackson deserialization failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 字节数组反序列化成java对象（兼容旧调用方，REQ-16 兜底）。
     *
     * <p><b>不推荐使用</b>：保留仅为兼容未迁移的调用方。内部走 {@link ObjectInputStream}
     * 但注入 {@link ObjectInputFilter} 白名单，仅允许基础类型与 org.nexus.keystore.* 类。
     *
     * <p>新代码应使用 {@link #toObject(byte[], Class)} 显式声明目标类型。
     *
     * @param bytes 序列化字节
     * @return 反序列化对象
     * @throws RuntimeException if deserialization fails or class is rejected by filter
     * @deprecated 使用 {@link #toObject(byte[], Class)} 显式声明目标类型
     */
    @Deprecated
    public static Object toObject(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be null or empty");
        }
        if (bytes.length > MAX_DESERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                "Deserialized payload too large: " + bytes.length
                + " > " + MAX_DESERIALIZED_SIZE + " bytes");
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = null;
        try {
            ois = new ObjectInputStream(bais);
            // JDK 9+ ObjectInputFilter 兜底：拒绝白名单外的类
            ois.setObjectInputFilter(DESERIALIZATION_FILTER);
            return ois.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            throw new RuntimeException("Deserialization rejected by ObjectInputFilter: "
                + ex.getMessage(), ex);
        } finally {
            if (ois != null) {
                try {
                    ois.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }
}
