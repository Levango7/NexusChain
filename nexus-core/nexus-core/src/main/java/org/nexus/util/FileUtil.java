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

import org.nexus.core.state.State;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件工具（继承自 java-nexuscore 上游）。
 *
 * <p><b>2026-11-07 生产就绪度审计核实：本类当前零调用方</b>
 * （`git grep 'FileUtil\.'` 命中 0 个文件）。保留原因仅为 LGPL 继承代码；
 * 若确认无外部消费者，可整体移除。</p>
 *
 * <p><b>接线警告</b>：{@link #recursiveDelete(String)} 会递归删除给定路径下的所有内容
 * 并且在删除前 {@code setWritable(true)} —— 若将来把它接到任何**外部可控**的路径上
 * （环境变量、请求参数、CLI 参数），必须先补根目录边界校验与白名单，
 * 否则等价于一个任意路径删除原语。审计的静态扫描正是把它标成了
 * path-traversal 入口（判定为误报的依据是"零调用方"，而不是"它安全"）。</p>
 */
public class FileUtil {

    public static List<String> recursiveList(String path) throws IOException {

        final List<String> files = new ArrayList<>();

        Files.walkFileTree(Paths.get(path), new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files.add(file.toString());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    /**
     * 递归删除文件/目录（含 {@code setWritable(true)}）。
     *
     * @deprecated 2026-11-07 审计核实<strong>零调用方</strong>（除自身递归）。保留仅为兼容
     *     LGPL 继承代码；**不建议在新代码中使用**。若必须使用，调用方负责保证
     *     {@code fileName} 不来自外部可控输入，否则构成任意路径删除。
     */
    @Deprecated
    public static boolean recursiveDelete(String fileName) {
        File file = new File(fileName);
        if (file.exists()) {
            //check if the file is a directory
            if (file.isDirectory()) {
                if ((file.list()).length > 0) {
                    for(String s:file.list()){
                        //call deletion of file individually
                        recursiveDelete(fileName + System.getProperty("file.separator") + s);
                    }
                }
            }

            file.setWritable(true);
            boolean result = file.delete();
            return result;
        } else {
            return false;
        }
    }
}