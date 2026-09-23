package org.nexus.gateway.developer;

import java.util.List;

/**
 * 快速入门指南 DTO — 包含指定语言的步骤化集成流程。
 */
public class QuickstartGuide {

    /** 编程语言 */
    private String language;

    /** 集成步骤列表 */
    private List<QuickstartStep> steps;

    public QuickstartGuide() {
    }

    public QuickstartGuide(String language, List<QuickstartStep> steps) {
        this.language = language;
        this.steps = steps;
    }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public List<QuickstartStep> getSteps() { return steps; }
    public void setSteps(List<QuickstartStep> steps) { this.steps = steps; }
}