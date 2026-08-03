package org.nexus.consortium.util.vm.wasm;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.cli.*;

//  运行使用 gradle runTestCommunicationTool -PappArgs="-c /media/sf_consortium/consortium/src/main/resources/local/main"
public class TestCommunicationTool {

    public static void main(String[] args) throws IOException, ParseException {
        CommandLineParser parser = new DefaultParser();
        final Options options = new Options();
        options.addOption("c", "cmd", true, "cmd");
        CommandLine line = parser.parse(options, args);
        String cmd = "";
        if (line.getOptionValue("cmd") != null && !line.getOptionValue("cmd").equals("")) {
            cmd = line.getOptionValue("cmd");
        } else {
            System.out.println("Cmd Command is empty");
            return;
        }
        Runtime run = Runtime.getRuntime();
        Process p = run.exec(cmd);
        OutputStream out = p.getOutputStream();
        byte[] in = getBytesByFile("src/main/resources/local/simple.wasm");
        if (in == null || in.length == 0) {
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        WasmFormat wasmFormat = new WasmFormat();
        wasmFormat.setBytes(in);
        List<WasmFormat.WasmParam> params = new ArrayList<>();
        params.add(new WasmFormat.WasmParam(WasmFormat.WasmParam.Wasm_Type.I32, 15));
        params.add(new WasmFormat.WasmParam(WasmFormat.WasmParam.Wasm_Type.I32, 30));
        wasmFormat.setParams(params);
        byte[] json = objectMapper.writeValueAsBytes(wasmFormat);
        out.write(json);
        out.flush();
        out.close();

        BufferedInputStream ins = new BufferedInputStream(p.getInputStream());
        BufferedReader br = new BufferedReader(new InputStreamReader(ins));
        String s;
        while ((s = br.readLine()) != null)
            System.out.println(s);

    }

    // 将文件转换成Byte数组
    private static byte[] getBytesByFile(String filePath) {
        File file = new File(filePath);
        try {
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1000);
            byte[] b = new byte[1000];
            int n;
            while ((n = fis.read(b)) != -1) {
                bos.write(b, 0, n);
            }
            fis.close();
            byte[] data = bos.toByteArray();
            bos.close();
            return data;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
