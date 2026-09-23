package org.nexus.gateway.developer;

import java.util.List;

/**
 * SDK 信息 DTO — 描述特定语言 SDK 的版本、下载和安装信息。
 */
public class SdkInfo {

    /** 编程语言：java, python, javascript, go */
    private String language;

    /** SDK 版本号 */
    private String version;

    /** 下载链接 */
    private String downloadUrl;

    /** 安装命令 */
    private String installCommand;

    /** 依赖列表 */
    private List<String> dependencies;

    /** 变更日志链接 */
    private String changelogUrl;

    public SdkInfo() {
    }

    public SdkInfo(String language, String version, String downloadUrl,
                   String installCommand, List<String> dependencies, String changelogUrl) {
        this.language = language;
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.installCommand = installCommand;
        this.dependencies = dependencies;
        this.changelogUrl = changelogUrl;
    }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getInstallCommand() { return installCommand; }
    public void setInstallCommand(String installCommand) { this.installCommand = installCommand; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public String getChangelogUrl() { return changelogUrl; }
    public void setChangelogUrl(String changelogUrl) { this.changelogUrl = changelogUrl; }
}