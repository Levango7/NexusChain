package org.nexus.core.account;

import org.apache.commons.io.IOUtils;
import org.nexus.keystore.util.JsonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.*;

//@Component
public class InitializeAccount {

    private JdbcTemplate tmpl;

    private boolean miner;

    public static class Initilize{
        private String version;
        private boolean initialize;

        public Initilize(String version, boolean initialize) {
            this.version = version;
            this.initialize = initialize;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isInitialize() {
            return initialize;
        }

        public void setInitialize(boolean initialize) {
            this.initialize = initialize;
        }
    }

//    @Autowired
    public InitializeAccount(JdbcTemplate tmpl, @Value("${nexus.consensus.enable-mining}") String miner ) throws IOException {
        this.tmpl=tmpl;
        this.miner= Boolean.parseBoolean(miner);
        if(!this.miner){
            long height=tmpl.queryForObject("select count(*) from header",Integer.class);
            String path=System.getProperty("user.dir")+ File.separator+"version.json";
            File file = new File(path);
            if(!file.exists()){
                path=System.getProperty("user.dir")+ File.separator+"nexus-core"+File.separator+"version.json";
                file = new File(path);
            }
            Resource resource = new FileSystemResource(file);
            String str = IOUtils.toString(new InputStreamReader(resource.getInputStream(),"UTF-8"));
            Initilize initilize = JsonUtils.fromJson(str, Initilize.class);
            if(height==0){//初始化
                if(initilize.getVersion().equals("v0.0.2")){
                    if(initilize.isInitialize()){
                        initilize.setInitialize(false);
                        String s = JsonUtils.toJson(initilize);
                        byte[] b = s.getBytes();
                        try (OutputStream os = new FileOutputStream(file)) {
                            os.write(b);
                        }
                    }
                }
            }else{
                if(initilize.getVersion().equals("v0.0.2")){
                    if(initilize.isInitialize()){
                        tmpl.batchUpdate("delete  from header where 1 = 1",
                                "delete from transaction where 1 = 1",
                                "delete from transaction_index where 1 = 1",
                                "delete from account where 1 = 1",
                                "delete from incubator_state where 1 = 1");
                        initilize.setInitialize(false);
                        String s = JsonUtils.toJson(initilize);
                        byte[] b = s.getBytes();
                        try (OutputStream os = new FileOutputStream(file)) {
                            os.write(b);
                        }
                    }
                }
            }
        }
    }
}
