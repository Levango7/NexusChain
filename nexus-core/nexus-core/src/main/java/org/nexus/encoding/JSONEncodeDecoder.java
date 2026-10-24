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

package org.nexus.encoding;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.commons.codec.binary.Hex;
import org.nexus.controller.Packet;
import org.nexus.genesis.Genesis;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JSONEncodeDecoder implements CoreTypesEncoder, CoreTypesDecoder {

    private static final Logger log = LoggerFactory.getLogger(JSONEncodeDecoder.class);
    public static class BytesSerializer extends StdSerializer<byte[]> {

        private static final long serialVersionUID = -5510353102817291511L;

        public BytesSerializer() {
            super(byte[].class);
        }

        @Override
        public void serialize(byte[] value, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonGenerationException {
            jgen.writeString(Hex.encodeHexString(value));
        }
    }

    public static class BytesDeserializer extends StdDeserializer<byte[]> {

        private static final long serialVersionUID = 1514703510863497028L;

        public BytesDeserializer() {
            super(byte[].class);
        }

        @Override
        public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            JsonNode node = p.getCodec().readTree(p);
            String encoded = node.asText();
            if(encoded == null || encoded.equals("")){
                return new byte[]{};
            }
            try {
                return Hex.decodeHex(encoded.toCharArray());
            } catch (RuntimeException | org.apache.commons.codec.DecoderException e) {
                throw new IOException("invalid hex encoding");
            }
        }
    }

    public byte[] encode(Object object) {
        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            SimpleModule module = new SimpleModule();
            module.addSerializer(byte[].class, new BytesSerializer());
            mapper.registerModule(module);
            return mapper.writeValueAsBytes(object);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("JSONEncodeDecoder: uncaught exception", e);
        }
        return null;
    }

    public <T> T decode(byte[] encoded, Class<T> valueType) {
        if (encoded == null) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            SimpleModule module = new SimpleModule();
            module.addDeserializer(byte[].class, new BytesDeserializer());
            mapper.registerModule(module);
            mapper.enable(JsonParser.Feature.ALLOW_COMMENTS);
            return mapper.readValue(encoded, valueType);
        } catch (RuntimeException | java.io.IOException e) {
            log.error("JSONEncodeDecoder: uncaught exception", e);
            return null;
        }
    }

    @Override
    public Block decodeBlock(byte[] data) {
        return decode(data, Block.class);
    }

    @Override
    public Transaction decodeTransaction(byte[] data) {
        return decode(data, Transaction.class);
    }

    // big-endian decode
    @Override
    public long decodeBlockNumber(byte[] data) {
        return BigEndian.decodeUint32(data);
    }

    @Override
    public List<Transaction> decodeBlockBody(byte[] body) {
        return Arrays.asList(decode(body, Transaction[].class));
    }

    @Override
    public byte[] encodeBlock(Block block) {
        return encode(block);
    }

    @Override
    public byte[] encodeTransaction(Transaction transaction) {
        return encode(transaction);
    }

    // big-endian encoding
    @Override
    public byte[] encodeBlockNumber(long value) {
        return BigEndian.encodeUint32(value);
    }

    @Override
    public byte[] encodeBlockBody(List<Transaction> body) {
        return encode(body);
    }

    @Override
    public List<byte[]> decodeHashes(byte[] hashes) {
        List<String> tmp = decode(hashes, ArrayList.class);
        List<byte[]> res = new ArrayList<>();
        try {
            for (String s : tmp) {
                res.add(Hex.decodeHex(s.toCharArray()));
            }
        } catch (RuntimeException | org.apache.commons.codec.DecoderException e) {
            log.error("JSONEncodeDecoder: uncaught exception", e);
        }
        return res;
    }

    @Override
    public byte[] encodeHashes(List<byte[]> hashes) {
        List<String> hs = new ArrayList<>();
        for (byte[] h : hashes) {
            hs.add(Hex.encodeHexString(h));
        }
        return encode(hs);
    }

    public Genesis decodeGenesis(byte[] data) {
        return decode(data, Genesis.class);
    }

    public byte[] encodeBlocks(List<Block> blocks) {
        return encode(blocks);
    }

    public List<Block> decodeBlocks(byte[] data) {
        return Arrays.asList(decodeTolerant(data, Block[].class));
    }

    public List<Transaction> decodeTransactions(byte[] txs) {
        return Arrays.asList(decodeTolerant(txs, Transaction[].class));
    }

    /**
     * P2P 线上解码（rest 形态区块/交易）：<b>容忍未知字段</b>。
     *
     * <p>为什么单独开这条路径（2026-10-25，F2 rest 形态验证发现）：服务端对区块做
     * JSON 序列化时，会把对象上的 getter 派生属性一并写出（如
     * {@code channelTransaction/stableCoinTransaction/paymentExtensionType/typeName/
     * bridgeTransaction}）；而 {@link #decode} 的 mapper 默认
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} ⇒ 客户端解区块直接失败，
     * rest 追块（SyncClient.getBlocks）永久拉不到块。线上数据本就应该容忍
     * 未知/新增字段（跨版本兼容），因此仅对 P2P 解码放宽；其余 {@link #decode}
     * 调用（本地快照/持久化）保持严格语义不变。</p>
     */
    private <T> T decodeTolerant(byte[] encoded, Class<T> valueType) {
        if (encoded == null) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            SimpleModule module = new SimpleModule();
            module.addDeserializer(byte[].class, new BytesDeserializer());
            mapper.registerModule(module);
            mapper.enable(JsonParser.Feature.ALLOW_COMMENTS);
            return mapper.readValue(encoded, valueType);
        } catch (RuntimeException | java.io.IOException e) {
            log.error("JSONEncodeDecoder: uncaught exception", e);
            return null;
        }
    }

    public byte[] encodeTransactions(List<Transaction> txs) {
        return encode(txs);
    }

    public byte[] encodePacket(Packet packet) {
        return encode(packet);
    }

    public static void main(String[] args) {
        JSONEncodeDecoder codec = new JSONEncodeDecoder();
        String encoded = new String(codec.encodePacket(new Packet(codec.encodeTransactions(Collections.singletonList(new Transaction())), 8)));
        log.info(encoded);
        Packet packet = codec.decode(encoded.getBytes(), Packet.class);
        log.info(String.valueOf(packet == null));
    }
}
