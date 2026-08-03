package org.nexus.wallet.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Wallet-local JSON helper.
 *
 * gson-2.8.5 is pinned in lib/, so we intentionally avoid JsonParser.parseString
 * (added in 2.8.6) and reuse this shared Gson instance instead.
 *
 * serializeNulls() mirrors fastjson's default behaviour of including null-valued
 * fields, so migrations of toJson/toJSONString remain byte-for-byte equivalent.
 */
public class JsonUtil {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();
    public static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
}
