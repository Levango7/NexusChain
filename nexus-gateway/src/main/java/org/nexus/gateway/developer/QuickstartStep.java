package org.nexus.gateway.developer;

/**
 * 快速入门步骤 DTO — 描述集成流程中的单个步骤。
 */
public class QuickstartStep {

    /** 步骤序号 */
    private int stepNumber;

    /** 步骤标题 */
    private String title;

    /** 步骤详细描述 */
    private String description;

    /** 步骤对应的代码片段（可选） */
    private String codeSnippet;

    public QuickstartStep() {
    }

    public QuickstartStep(int stepNumber, String title, String description, String codeSnippet) {
        this.stepNumber = stepNumber;
        this.title = title;
        this.description = description;
        this.codeSnippet = codeSnippet;
    }

    public int getStepNumber() { return stepNumber; }
    public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCodeSnippet() { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }
}