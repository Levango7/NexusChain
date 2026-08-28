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

package org.nexus.protobuf;

import com.google.protobuf.ByteString;

import java.io.IOException;

public class GenerateClass {

    public static void main(String[] args) throws IOException, InterruptedException {

        /*
         *protobuf generation method
         *
         * */
        String protoFile = "Protocol.proto";
        String path = "C:/Users/Administrator/IdeaProjects/java-nexuscore/nexus-core/src/main/java/org/nexus/protobuf/tcp";
        String out = "C:/Users/Administrator/IdeaProjects/java-nexuscore/nexus-core/src/main/java";
        // P4-SA: Runtime.exec(字符串) → ProcessBuilder 数组参数（不经 shell，
        // 消除 COMMAND_INJECTION；路径含空格也安全）
        String protoc = "D:/protoc-3.7.0-win64/bin/protoc.exe";
        Process process = new ProcessBuilder(protoc, "-I=" + path, "--java_out=" + out,
                path + "/" + protoFile)
                .redirectErrorStream(true)
                .start();
        System.out.println(process.waitFor() == 0 ? "完成" : "protoc 执行失败");

    }
}