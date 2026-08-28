package org.nexus.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.keystore.util.JsonUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import org.springframework.stereotype.Component;
import org.nexus.ApiResult.APIResult;
import org.nexus.controller.RPCClient;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.p2p.PeerServer;
import org.nexus.service.CommandService;
import org.nexus.service.HatchService;
import org.nexus.sync.TransactionHandler;
import org.nexus.util.JWTUtil;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;


@Component
public class Fifo implements ApplicationRunner, ApplicationListener<Fifo.FifoMessageEvent> {

    private static final Logger logger = LoggerFactory.getLogger(Fifo.class);

    @Autowired
    private ApplicationContext ctx;

    private FileReader reader;

    private FileWriter writer;

    @Autowired
    CommandService commandService;

    @Value("${p2p.mode}")
    private String p2pMode;

    @Autowired
    private TransactionHandler transactionHandler;

    @Autowired
    RPCClient RPCClient;

    @Autowired
    IpcConfig ipcConfig;

    @Value("${node-character}")
    private String character;

    @Autowired
    private PeerServer peerServer;

    @Autowired
    private Block genesis;

    @Autowired
    private AccountDB accountDB;

    @Autowired
    HatchService hatchService;

    @Autowired
    NexusChainBlockChain bc;

    @Autowired
    JSONEncodeDecoder encodeDecoder;

    @Autowired
    org.nexus.command.Configuration Configuration;

    private static final String InvalidParams = "params is invalid";

    private static final String ModifySuccess = "modify success";

    private static final String InvalidCron = "cron is invalid";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isLinuxSystem()) {
            return;
        }
        initFifo();
        while (true) {
            char[] a = new char[1024];
            if ((reader.read(a)) != -1) {
                ctx.publishEvent(new FifoMessageEvent(this, String.valueOf(a)));
            } else {
                Thread.sleep(100);
                continue;
            }
            if (!String.valueOf(a).equals("") && String.valueOf(a).contains("exit")) {
                break;
            }
        }
        closeFifo();
    }

    private void initFifo() throws IOException, InterruptedException {
        String dir = CreateFifoDir();
        File readFile = new File(dir + File.separator + "pipe.in");
        if (!readFile.exists()) {
            readFile = createFifoPipe(dir + File.separator + "pipe.in");
        }
        File writeFile = new File(dir + File.separator + "pipe.out");
        if (!writeFile.exists()) {
            writeFile = createFifoPipe(dir + File.separator + "pipe.out");
        }
        reader = new FileReader(readFile);
        writer = new FileWriter(writeFile);
    }

    private String CreateFifoDir() {
        File filePath = new File(System.getProperty("user.home") + File.separator + "ipc");
        //判断该文件夹是否已存在
        if (!filePath.exists()) {
            filePath.mkdirs();
        }
        return filePath.getAbsolutePath();
    }

    private void closeFifo() throws IOException {
        reader.close();
        writer.close();
    }

    /**
     * isLinuxSystem 是否是linux系统
     *
     * @return bool
     */
    private boolean isLinuxSystem() {
        String os = System.getProperty("os.name");
        return !os.toLowerCase().startsWith("win");
    }

    /**
     * createFifoPipe 创建 fifo 文件
     *
     * @param fifoName 文件名
     * @return file
     * @throws IOException
     * @throws InterruptedException
     */
    private File createFifoPipe(String fifoName) throws IOException, InterruptedException {
        // P4-SA: Runtime.exec → ProcessBuilder（数组参数，不经 shell 解析，
        // 消除 COMMAND_INJECTION；fifoName 为构造路径非用户可控输入）
        Process process = new ProcessBuilder("mkfifo", fifoName)
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        return new File(fifoName);
    }


    @Override
    public void onApplicationEvent(FifoMessageEvent event) {
        try {
            JsonNode jo = JsonUtils.readTree(event.message);
            String message = jo.path("message").asText();
            String type = jo.path("type").asText();
            String result = dealMessage(message, type);
            writer.write(result);
            writer.flush();
        } catch (Exception e) {
            logger.error("Failed to handle fifo message: {}", event.message, e);
        }
    }

    private String dealMessage(String message, String type) {
        switch (type) {
            case "sendTranInfo":
                return sendTranInfo(message);
            case "getNodeInfo":
                return getNodeInfo();
            case "getBalance":
                return getBalance(message);
            case "sendNonce":
                return sendNonce(message);
            case "height":
                return getHeight();
            case "blockHash":
                return blockHash(message);
            case "transactionConfirmed":
                return transactionConfirmed(message);
            case "getTransactionHeight":
                return getTransactionHeight(message);
            case "transaction":
                return getTransaction(message);
            case "getTransactionBlock":
                return getTransactionBlock(message);
            case "modifyVersion":
                return setVersion(message);
            case "modifyQueuedToPendingCycle":
                return modifyQueuedToPendingCycle(message);
            case "modifyClearCycle":
                return modifyClearCycle(message);
            case "setIsLocalOnly":
                return setIsLocalOnly(message);
            case "modifyFeeLimit":
                return modifyFeeLimit(message);
            case "setP2PMode":
                return setP2PMode(message);
            case "setQueuedMaxSize":
                return setQueuedMaxSize(message);
            case "setPendingMaxSize":
                return setPendingMaxSize(message);
            case "getJWTToken":
                return getJWTToken(message);
        }
        return "";
    }

    private String getJWTToken(String message) {
        long ttlMillis = Long.parseLong(message);
        return "JWT Token is: " + JWTUtil.createJWT(ttlMillis);
    }

    private String setPendingMaxSize(String message) {
        int pendingMaxSize = Integer.parseInt(message);
        ipcConfig.setPendingMaxSize(pendingMaxSize);
        return ModifySuccess;
    }

    private String setQueuedMaxSize(String message) {
        int queuedMaxSize = Integer.parseInt(message);
        ipcConfig.setQueuedMaxSize(queuedMaxSize);
        return ModifySuccess;
    }

    private String setP2PMode(String message) {
        if (!message.equals("rest") && !message.equals("grpc")) {
            return InvalidParams;
        }
        ipcConfig.setP2pMode(message);
        return ModifySuccess;
    }

    private String modifyFeeLimit(String message) {
        int feeLimit = Integer.parseInt(message);
        ipcConfig.setFeeLimit(feeLimit);
        return ModifySuccess;
    }

    private String setIsLocalOnly(String message) {
        boolean isLocalOnly = Boolean.parseBoolean(message);
        ipcConfig.setLocalOnly(isLocalOnly);
        return ModifySuccess;
    }

    private String modifyClearCycle(String cron) {
        if (!validateCron(cron)) {
            return InvalidCron;
        }
        ipcConfig.setClearCycle(cron);
        return ModifySuccess;
    }

    private String modifyQueuedToPendingCycle(String cron) {
        if (!validateCron(cron)) {
            return InvalidCron;
        }
        ipcConfig.setQueuedToPendingCycle(cron);
        return ModifySuccess;
    }

    private boolean validateCron(String cron) {
        return CronExpression.isValidExpression(cron);
    }

    private String setVersion(String ver) {
        ipcConfig.setVersion(ver);
        return ModifySuccess;
    }

    private String getTransactionBlock(String message) {
        JsonNode jo = JsonUtils.readTree(message);
        String blockHash = jo.path("blockHash").asText();
        int type = jo.path("type").asInt();
        try {
            List<Map<String, Object>> transactionList = accountDB.getTranBlockList(Hex.decodeHex(blockHash.toCharArray()), type);
            ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
            jsonObject.putPOJO("transactionList", transactionList);
            return jsonObject.toString();
        } catch (DecoderException e) {
            return InvalidParams;
        }
    }

    private String getTransaction(String txHash) {
        try {
            byte[] h = Hex.decodeHex(txHash);
            Transaction tx = bc.getTransaction(h);
            if (tx != null) {
                return new String(encodeDecoder.encodeTransaction(tx));
            }
        } catch (RuntimeException | org.apache.commons.codec.DecoderException e) {
            return InvalidParams;
        }
        return "";
    }


    private String getTransactionHeight(String message) {
        ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
        JsonNode jo = JsonUtils.readTree(message);
        int height = jo.path("height").asInt();
        int type = jo.path("type").asInt();
        List<Map<String, Object>> transactionList = accountDB.getTranList(height, type);
        jsonObject.putPOJO("transactionList", transactionList);
        return jsonObject.toString();
    }

    private String transactionConfirmed(String txHash) {
        Block current = bc.currentHeader();
        Transaction tx;
        try {
            tx = bc.getTransaction(Hex.decodeHex(txHash));
            ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
            jsonObject.put("message", "not_confirmed");
            if (current.nHeight - tx.height > 2) {
                jsonObject.put("message", "confirmed");
            }
            return jsonObject.toString();
        } catch (RuntimeException | org.apache.commons.codec.DecoderException e) {
            return InvalidParams;
        }
    }

    private String blockHash(String txHash) {
        Transaction tx;
        try {
            tx = bc.getTransaction(Hex.decodeHex(txHash));
            ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
            jsonObject.put("blockHash", Hex.encodeHexString(tx.blockHash));
            jsonObject.put("height", tx.height);
            return jsonObject.toString();
        } catch (RuntimeException | org.apache.commons.codec.DecoderException e) {
            return InvalidParams;
        }
    }

    private String sendNonce(String publicKeyHash) {
        long nonce = 0;
        try {
            nonce = accountDB.getNonce(Hex.decodeHex(publicKeyHash));
            ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
            jsonObject.put("nonce", nonce);
            return jsonObject.toString();
        } catch (DecoderException e) {
            return InvalidParams;
        }
    }

    private String getBalance(String message) {
        try {
            byte[] publicKey = Hex.decodeHex(message);
            String balance = String.valueOf(accountDB.getBalance(publicKey));
            ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
            jsonObject.put("balance", balance);
            return jsonObject.toString();
        } catch (DecoderException e) {
            return InvalidParams;
        }
    }

    private String getHeight() {
        Block current = bc.currentHeader();
        ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
        jsonObject.put("height", current.nHeight);
        return jsonObject.toString();
    }


    private String getNodeInfo() {
        String networkType;
        if (genesis.getHashHexString().equals("add27a8cf6a42e30334e9f100cd42643ac0e2eed6896e9289bbcf8a8adaf814b")) {
            networkType = "production";
        } else {
            networkType = "testing";
        }
        int port = peerServer.getPort();
        String publicKey = peerServer.getNodePubKey();
        String IP = peerServer.getIP();
        ObjectNode jsonObject = JsonUtils.MAPPER.createObjectNode();
        jsonObject.put("ip", IP);
        jsonObject.put("publicKey", publicKey);
        jsonObject.put("character", character);
        jsonObject.put("version", ipcConfig.getVersion());
        jsonObject.put("networkType", networkType);
        jsonObject.put("port", port);
        return jsonObject.toString();
    }

    private String sendTranInfo(String tranInfo) {
        try {
            byte[] transInfo = Hex.decodeHex(tranInfo);
            APIResult result = commandService.verifyTransfer(transInfo);
            if (result.getCode() == 2000) {
                Transaction t = (Transaction) result.getData();
                if (p2pMode.equals("rest")) {
                    RPCClient.broadcastTransactions(Collections.singletonList(t));
                } else {
                    transactionHandler.broadcastTransactions(Collections.singletonList(t));
                }
            }
            return JsonUtils.toJson(result);
        } catch (DecoderException e) {
            APIResult apiResult = new APIResult();
            apiResult.setCode(5000);
            apiResult.setMessage("Error");
            return JsonUtils.toJson(apiResult);
        }
    }

    public static class FifoMessageEvent extends ApplicationEvent {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        FifoMessageEvent(Object source, String message) {
            super(source);
            this.message = message;
        }
    }

}
