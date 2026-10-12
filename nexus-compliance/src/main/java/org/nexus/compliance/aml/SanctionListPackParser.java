package org.nexus.compliance.aml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 制裁名单「离线包」解析器（2026-10-12 新增）。
 *
 * <p><b>为什么需要</b>：{@link InMemorySanctionListChecker#loadFromFile} 最初只认
 * "一行一条 + 逗号取首列"。而真实名单是<strong>官方固定格式</strong>：
 * OFAC SDN 是 CSV（名字在 {@code SDN_Name} 列，且大量条目形如 {@code -0-}）
 * 与 UN 综合名单是 XML（姓/名拆分在 {@code FIRST_NAME}/{@code SECOND_NAME}）。
 * 用"取首列"去喂这些文件，得到的是一列 ID 而不是姓名 ⇒ 名单<strong>看起来加载了、
 * 实际上永远匹配不到人</strong>（本仓反复出现的"结构在、数据不对"型缺陷）。</p>
 *
 * <p><b>支持的格式</b>（{@link Format#AUTO} 自动识别）：</p>
 * <ul>
 *   <li>{@link Format#OFAC_CSV}：表头含 {@code SDN_Name} 或 {@code ent_num}；
 *       逐字符 CSV 解析（支持引号内逗号），跳过 {@code -0-} 占位与空值</li>
 *   <li>{@link Format#UN_XML}：{@code CONSOLIDATED_LIST} 结构的
 *       {@code INDIVIDUAL}（FIRST/SECOND/THIRD_NAME 拼接）与 {@code ENTITY}（ENTITY_NAME）</li>
 *   <li>{@link Format#LINES}：兜底——一行一条、{@code #} 注释、空行忽略、含逗号取首列</li>
 * </ul>
 *
 * <p><b>安全</b>：XML 走 JDK 内置 DOM，并<strong>关掉 DTD 与外部实体</strong>
 * （{@code disallow-doctype-decl=true} + 禁用外部实体 + {@code FEATURE_SECURE_PROCESSING}）。
 * 名单文件来自客户/外网，不设防就是 XXE（读本地文件/SSRF）的现成入口。</p>
 */
public final class SanctionListPackParser {

    private SanctionListPackParser() {
    }

    /** 名单包格式。 */
    public enum Format {
        AUTO, OFAC_CSV, UN_XML, LINES
    }

    /** 解析结果。 */
    public static final class Result {
        private final Format format;
        private final List<String> entries;
        private final int skipped;

        Result(Format format, List<String> entries, int skipped) {
            this.format = format;
            this.entries = List.copyOf(entries);
            this.skipped = skipped;
        }

        public Format getFormat() { return format; }
        public List<String> getEntries() { return entries; }
        public int getSkipped() { return skipped; }
        public int getCount() { return entries.size(); }
    }

    /**
     * 解析名单包内容。
     *
     * @param content    文件内容（UTF-8 文本）
     * @param formatHint 期望格式；{@link Format#AUTO} 或 null 时自动识别
     */
    public static Result parse(String content, Format formatHint) {
        if (content == null || content.isBlank()) {
            return new Result(Format.LINES, List.of(), 0);
        }
        Format format = (formatHint == null || formatHint == Format.AUTO)
                ? detect(content) : formatHint;
        return switch (format) {
            case OFAC_CSV -> parseCsv(content);
            case UN_XML -> parseUnXml(content);
            default -> parseLines(content);
        };
    }

    /** 自动识别：XML 看是否以 {@code <} 开头；CSV 看表头是否含 OFAC 标志列。 */
    static Format detect(String content) {
        String head = content.stripLeading();
        if (head.startsWith("<")) {
            return Format.UN_XML;
        }
        String firstLine = firstNonCommentLine(content);
        if (firstLine != null) {
            String lower = firstLine.toLowerCase(Locale.ROOT);
            if (lower.contains("sdn_name") || lower.contains("ent_num")
                    || lower.contains("sdn_type")) {
                return Format.OFAC_CSV;
            }
        }
        return Format.LINES;
    }

    // ---------------------------------------------------------------- OFAC CSV

    /**
     * OFAC SDN CSV：默认文件无表头时按官方列序（第 1 列 ent_num、第 2 列 SDN_Name）取；
     * 有表头时按表头定位列。{@code -0-}、{@code ""} 一律视为缺失并跳过。
     */
    static Result parseCsv(String content) {
        List<String> lines = splitLines(content);
        List<String> out = new ArrayList<>();
        int skipped = 0;

        int nameColumn = 1;                 // OFAC 官方列序：0=ent_num, 1=SDN_Name
        int startRow = 0;
        if (!lines.isEmpty()) {
            List<String> header = parseCsvRow(lines.get(0));
            int idx = indexOfIgnoreCase(header, "SDN_Name");
            if (idx < 0) {
                idx = indexOfIgnoreCase(header, "name");
            }
            if (idx >= 0) {
                nameColumn = idx;
                startRow = 1;               // 有表头 → 从第 2 行开始是数据
            } else if (indexOfIgnoreCase(header, "ent_num") >= 0) {
                startRow = 1;               // 有表头但无 name 列（异常文件）：仍跳过表头
            }
        }

        for (int i = startRow; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isIgnorable(line)) {
                continue;
            }
            List<String> row = parseCsvRow(line);
            String name = row.size() > nameColumn ? row.get(nameColumn) : "";
            name = cleanName(name);
            if (name.isEmpty()) {
                skipped++;
                continue;
            }
            out.add(name);
        }
        return new Result(Format.OFAC_CSV, out, skipped);
    }

    /** 逐字符 CSV 行解析：支持双引号包裹字段（引号内的逗号不切分）与 "" 转义。 */
    static List<String> parseCsvRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');    // "" → 字面量引号
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',' || c == ';') {
                fields.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString().trim());
        return fields;
    }

    // ---------------------------------------------------------------- UN XML

    /**
     * UN 综合名单 XML：取 {@code INDIVIDUAL} 的 FIRST/SECOND/THIRD_NAME 与
     * {@code ENTITY} 的 ENTITY_NAME（别名 ALIAS_NAME 不入名单主键，避免误伤同名）。
     * 解析失败（含含 DTD 的恶意文件）→ 抛 {@link IllegalArgumentException}，由调用方 fail-closed。
     */
    static Result parseUnXml(String content) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("名单 XML 解析失败（已拒绝含 DTD/外部实体的文件）: "
                    + e.getMessage(), e);
        }

        List<String> out = new ArrayList<>();
        int skipped = 0;

        NodeList individuals = doc.getElementsByTagName("INDIVIDUAL");
        for (int i = 0; i < individuals.getLength(); i++) {
            Element el = (Element) individuals.item(i);
            String name = join(part(el, "FIRST_NAME"), part(el, "SECOND_NAME"), part(el, "THIRD_NAME"));
            if (name.isEmpty()) {
                skipped++;
            } else {
                out.add(name);
            }
        }
        NodeList entities = doc.getElementsByTagName("ENTITY");
        for (int i = 0; i < entities.getLength(); i++) {
            Element el = (Element) entities.item(i);
            String name = cleanName(part(el, "ENTITY_NAME"));
            if (name.isEmpty()) {
                skipped++;
            } else {
                out.add(name);
            }
        }
        return new Result(Format.UN_XML, out, skipped);
    }

    /** 取第一个同名子元素的文本（UN 名单里 FIRST_NAME 等是 INDIVIDUAL 的直接子元素）。 */
    private static String part(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && parent.equals(n.getParentNode())) {
                String t = n.getTextContent();
                return t == null ? "" : t.trim();
            }
        }
        return "";
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String v = cleanName(p);
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- LINES 兜底

    /** 一行一条：{@code #} 注释与空行忽略；含逗号/分号时取第一列（与旧行为一致）。 */
    static Result parseLines(String content) {
        List<String> out = new ArrayList<>();
        int skipped = 0;
        for (String line : splitLines(content)) {
            if (isIgnorable(line)) {
                continue;
            }
            String v = line;
            if (v.contains(",") || v.contains(";")) {
                List<String> row = parseCsvRow(v);
                v = row.isEmpty() ? "" : row.get(0);
            }
            v = cleanName(v);
            if (v.isEmpty()) {
                skipped++;
            } else {
                out.add(v);
            }
        }
        return new Result(Format.LINES, out, skipped);
    }

    // ---------------------------------------------------------------- 工具

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\r?\n")) {
            lines.add(line);
        }
        return lines;
    }

    private static boolean isIgnorable(String line) {
        if (line == null) {
            return true;
        }
        String v = line.trim();
        return v.isEmpty() || v.startsWith("#");
    }

    private static String firstNonCommentLine(String content) {
        for (String line : splitLines(content)) {
            if (!isIgnorable(line)) {
                return line;
            }
        }
        return null;
    }

    private static int indexOfIgnoreCase(List<String> values, String target) {
        for (int i = 0; i < values.size(); i++) {
            if (target.equalsIgnoreCase(values.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 清洗姓名：去首尾空白与包裹引号；{@code -0-}（OFAC 的空值占位）视为缺失。
     */
    static String cleanName(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.isEmpty() || "-0-".equals(v) || "n/a".equalsIgnoreCase(v)) {
            return "";
        }
        return v;
    }
}
