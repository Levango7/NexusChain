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

package org.nexus.util;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 许可证头批量写入工具（继承自 java-nexuscore 上游的**一次性开发脚本**）。
 *
 * <p><b>2026-11-07 生产就绪度审计标注</b>：`main` 里被处理的路径是写死的
 * 作者本机绝对路径（{@code C:\Users\Administrator\IdeaProjects\...\Start.java}），
 * 即它只在原作者机器上跑过一次。</p>
 *
 * <p><b>不要在构建 / CI / 生产流程中调用本类</b>：它会就地改写 {@code .java} 源文件，
 * 且路径来自调用方。若确需重跑，请先把 {@code main} 里的路径改成受控参数，
 * 并先在小范围验证（工具本身不做任何边界校验）。</p>
 *
 * <p>静态扫描把它标为 path-traversal 入口——判定为误报的依据是"无服务端调用方 + 一次性工具"，
 * 而不是"它安全"。</p>
 */
public class CopyrightUtil {
    private static int count = 0;
    private static List<String> fail = new ArrayList<>();
    private static List<String> wrong = new ArrayList<>();

    public static void main(String[] args) {
        String licence="/*\n" +
                " * Copyright (c) [2018]\n" +
                " * This file is part of the java-nexuscore\n" +
                " *\n" +
                " * The java-nexuscore is free software: you can redistribute it and/or modify\n" +
                " * it under the terms of the GNU Lesser General Public License as published by\n" +
                " * the Free Software Foundation, either version 3 of the License, or\n" +
                " * (at your option) any later version.\n" +
                " *\n" +
                " * The java-nexuscore is distributed in the hope that it will be useful,\n" +
                " * but WITHOUT ANY WARRANTY; without even the implied warranty of\n" +
                " * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the\n" +
                " * GNU Lesser General Public License for more details.\n" +
                " *\n" +
                " * You should have received a copy of the GNU Lesser General Public License\n" +
                " * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.\n" +
                " */";
        addLicenceForJavaFile(new File("C:\\Users\\Administrator\\IdeaProjects\\java-nexuscore\\nexus-core\\src\\main\\java\\org\\nexus\\Start.java"),licence);
        System.out.println("为 "+count+" 个添加");
        if(fail.size()>0){
            System.out.println("处理失败个数 "+fail.size());
            for(String f : fail){
                System.out.println("        "+f);
            }
        }
        if(wrong.size()>0){
            System.out.println("JAVA源代码错误个数 "+wrong.size());
            for(String w : wrong){
                System.out.println("        "+w);
            }
        }
    }

    private static void addLicenceForJavaFile(File path, String licence) {
        if (path != null && path.exists()) {
            //处理文件夹
            if (path.isDirectory()) {
                String[] children = path.list();
                for (int i = 0; i < children.length; i++) {
                    File child = new File(path.getPath() + System.getProperty("file.separator") + children[i]);
                    //递归处理
                    addLicenceForJavaFile(child, licence);
                }
            } else {
                //处理java文件
                if (path.getName().toLowerCase().endsWith(".java")) {
                    System.out.println(path.getAbsolutePath());
                    count++;
                    try {
                        byte[] content;
                        try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
                            content = new byte[ (int) f.length()];
                            f.readFully(content);
                        }
                        String text = new String(content);
                        text = text.trim();
                        while (text.startsWith("/n")) {
                            text = text.substring(1);
                        }
                        //如果已经有同样的licence，则pass
                        int pos = text.indexOf(licence);
                        if(pos!=-1){
                            return;
                        }
                        if (text.indexOf("package") != -1) {
                            text = text.substring(text.indexOf("package"));
                        }
                        else if (text.indexOf("package") == -1 && text.indexOf("import") != -1) {
                            text = text.substring(text.indexOf("import"));
                        }
                        else if (text.indexOf("package") == -1 && text.indexOf("import") == -1 && text.indexOf("/**") != -1 && text.indexOf("public class") != -1 && text.indexOf("/**")<text.indexOf("public class") ) {
                            text = text.substring(text.indexOf("/**"));
                        }
                        else if (text.indexOf("package") == -1 && text.indexOf("import") == -1 && text.indexOf("public class") != -1 && ( text.indexOf("/**")>text.indexOf("public class") || text.indexOf("/**")==-1 )) {
                            text = text.substring(text.indexOf("public class"));
                        }else{
                            wrong.add(path.getAbsolutePath());
                            return;
                        }
                        try (FileWriter writer = new FileWriter(path)) {
                            writer.write(licence);
                            writer.write("\n\n");
                            writer.write(text);
                        }
                    }
                    catch (RuntimeException | java.io.IOException ex) {
                        fail.add(path.getAbsolutePath());
                    }
                }
            }
        }
    }
}