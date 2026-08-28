package org.nexus.controller;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试辅助 Controller。
 *
 * <p>仅允许在开发/本地联调环境加载（dev / local profile），生产环境默认不暴露。
 * 审计发现（2026-08-29）：此接口无鉴权且直接查询交易库，必须环境隔离。
 */
@RestController
@Profile({"dev", "local"})
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    @Autowired
    JdbcTemplate tmpl;

    @RequestMapping(value="/cheackTxlist",method = RequestMethod.GET)
    public Object cheackTxList(@RequestParam(value = "message") String message){
        List<String> strings=new ArrayList<>();
        message = message.replace("\"", "");
        try{
           String[] list= message.trim().replaceAll("\r|\n", "").split(",");
            for(int x=0;x<list.length;x++){
                byte[] hash=Hex.decodeHex(list[x].toCharArray());
                int s=tmpl.queryForObject("select count(*) from transaction t where t.tx_hash=?", new Object[]{hash}, Integer.class);
                if(s==0){
                    strings.add(list[x]);
                }
            }
            return strings;
        }catch (RuntimeException | org.apache.commons.codec.DecoderException e){
            log.error("cheackTxList failed: {}", e.getMessage(), e);
            return "Exception";
        }
    }
}
