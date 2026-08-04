package org.nexus.compliance.aml;

/**
 * 制裁名单检查器接口。
 * <p>
 * 负责对姓名或地址进行制裁名单匹配。
 * </p>
 */
public interface SanctionListChecker {

    /**
     * 检查姓名或地址是否命中制裁名单。
     *
     * @param nameOrAddress 姓名或地址
     * @return 命中结果数组（空数组表示未命中）
     */
    SanctionHit[] check(String nameOrAddress);

    /** 制裁命中结果 */
    class SanctionHit {

        /** 命中的名单名称 */
        private String listName;

        /** 匹配度（0~1） */
        private double matchScore;

        /** 命中条目原始信息 */
        private String rawEntry;

        public SanctionHit() {}

        public SanctionHit(String listName, double matchScore, String rawEntry) {
            this.listName = listName;
            this.matchScore = matchScore;
            this.rawEntry = rawEntry;
        }

        public String getListName() { return listName; }
        public void setListName(String listName) { this.listName = listName; }

        public double getMatchScore() { return matchScore; }
        public void setMatchScore(double matchScore) { this.matchScore = matchScore; }

        public String getRawEntry() { return rawEntry; }
        public void setRawEntry(String rawEntry) { this.rawEntry = rawEntry; }
    }
}