package org.nexus.compliance.aml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 制裁名单启动加载器（P0 补齐 2026-10-11）。
 *
 * <p>应用就绪时从 {@code nexus.compliance.sanction-list-path} 读取名单文件灌入
 * {@link InMemorySanctionListChecker}——此前名单**没有任何数据入口**（{@code addEntry}
 * 全仓零调用方），AML 筛查恒为空。</p>
 *
 * <p><b>显式告警</b>："未配置路径" 与 "名单为空" 都记 WARN——空名单意味着筛查形同虚设，
 * 必须在日志里看得见（而不是静默通过一切）。</p>
 */
@Component
public class SanctionListStartupLoader {

    private static final Logger log = LoggerFactory.getLogger(SanctionListStartupLoader.class);

    private final InMemorySanctionListChecker checker;
    private final String listPath;

    public SanctionListStartupLoader(
            InMemorySanctionListChecker checker,
            @Value("${nexus.compliance.sanction-list-path:}") String listPath) {
        this.checker = checker;
        this.listPath = listPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        reload();
    }

    /**
     * 重新加载名单（启动时与运营主动触发共用）。
     *
     * @return 本次加载的有效条目数（路径未配置返回 0）
     */
    public int reload() {
        if (listPath == null || listPath.isBlank()) {
            log.warn("[SanctionList] 未配置 nexus.compliance.sanction-list-path —— "
                    + "制裁名单为空，AML 筛查不会命中任何对象（当前条目数={}）", checker.size());
            return 0;
        }
        int loaded = checker.loadFromFile(listPath);
        if (checker.isEmpty()) {
            log.warn("[SanctionList] 名单仍为空（loaded={}）——筛查形同虚设，请核对名单文件内容", loaded);
        }
        return loaded;
    }

    public InMemorySanctionListChecker checker() {
        return checker;
    }
}
