package org.nexus.gateway.developer;

/**
 * 代码示例 DTO — 包含指定编程语言的 API 调用代码示例。
 */
public class CodeSample {

    /** 编程语言：java, python, javascript, go, curl */
    private String language;

    /** 代码内容 */
    private String code;

    /** 示例描述 */
    private String description;

    public CodeSample() {
    }

    public CodeSample(String language, String code, String description) {
        this.language = language;
        this.code = code;
        this.description = description;
    }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}