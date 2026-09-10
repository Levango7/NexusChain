package org.nexus.signing.util;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求工具。
 *
 * <p>从 {@code org.nexus.wallet.Utils.HttpRequestUtil}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.util}。</p>
 *
 * <p>NodeController 依赖本类进行链节点 RPC 调用。迁移期保留，未来换 WebClient。</p>
 *
 * <p>健壮性修复（质量审查 Top4，2026-09-10）：原实现无 connect/read 超时
 * （链节点无响应时签名线程永久挂起——上层还有 Feign 5s 超时，最终表现为
 * "签名超时但线程泄漏"）；字符串拼接 O(n²)。现设 connect 5s / read 30s
 * （链 RPC 允许慢，但绝不无限等待）+ StringBuilder。</p>
 */
public class HttpRequestUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestUtil.class);

    /** 连接建立超时（ms）——链节点不可达时快速失败。 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 响应读取超时（ms）——链 RPC 可能慢（全节点同步查询），但 30s 后放弃。 */
    private static final int READ_TIMEOUT_MS = 30_000;

    public static String sendPost(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        StringBuilder result = new StringBuilder();
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection conn = realUrl.openConnection();
            // 超时兜底（Top4）：未设置时链节点无响应 = 调用线程永久挂起
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // 设置通用的请求属性
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL的响应
            in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.error("sendPost failed: url={}, param={}", url, param, e);
            JsonObject jo = new JsonObject();
            jo.addProperty("message","Connection refused");
            jo.addProperty("data","");
            jo.addProperty("code","5000");
            return jo.toString();
        }
        //使用finally块来关闭输出流、输入流
        finally{
            try{
                if(out!=null){
                    out.close();
                }
                if(in!=null){
                    in.close();
                }
            }
            catch(IOException ex){
                log.warn("Failed to close stream in sendPost", ex);
            }
        }
        return result.toString();
    }

    public static String sendGet(String url, String param) {
        StringBuilder result = new StringBuilder();
        BufferedReader in = null;
        try {
            String urlNameString = url + "?" + param;
            URL realUrl = new URL(urlNameString);
            // 打开和URL之间的连接
            URLConnection connection = realUrl.openConnection();
            // 超时兜底（Top4）：同 sendPost
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            // 设置通用的请求属性
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            // 建立实际的连接
            connection.connect();
            // 定义 BufferedReader输入流来读取URL的响应
            in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            log.error("sendGet failed: url={}, param={}", url, param, e);
            JsonObject jo = new JsonObject();
            jo.addProperty("message","Connection refused");
            jo.addProperty("data","");
            jo.addProperty("code","5000");
            return jo.toString();
        }
        // 使用finally块来关闭输入流
        finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e2) {
                log.warn("Failed to close stream in sendGet", e2);
            }
        }
        return result.toString();
    }

    public static void main(String[] args) throws IOException {


    }
}