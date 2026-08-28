package org.springframework.boot.autoconfigure.data.web;

/**
 * Spring Boot 4.0 兼容 shim：在旧包路径提供 {@code SpringDataWebProperties}。
 *
 * <p><b>背景</b>：Spring Boot 4.0 将 {@code SpringDataWebProperties} 从
 * {@code org.springframework.boot.autoconfigure.data.web} 包迁移到
 * {@code org.springframework.boot.data.autoconfigure.web.DataWebProperties}，
 * 但 spring-cloud-openfeign 4.1.3 的 {@code FeignClientsConfiguration} 仍引用
 * 旧包路径的类（字段 {@code springDataWebProperties} 为
 * {@code @Autowired(required = false)}）。类加载时若旧类不存在会抛出
 * {@code NoClassDefFoundError}，导致 ApplicationContext 加载失败。</p>
 *
 * <p><b>方案</b>：在本兼容 shim 中保留旧包路径与完整 getter/setter API，
 * 方法返回 Spring Boot 默认值（page/size/sort），使 FeignClientsConfiguration
 * 能成功加载。该 shim 不注册为 Spring bean——FeignClientsConfiguration 的字段
 * 标注 {@code @Autowired(required = false)}，注入不到时为 null，
 * Feign 的 PageableSpringEncoder 将使用其内置默认参数名，与 Spring Boot 默认一致。</p>
 *
 * <p><b>生命周期</b>：升级 spring-cloud-openfeign 至兼容 Spring Boot 4.0 的版本后，
 * 本类应删除。</p>
 *
 * @since 2.30.0（Spring Boot 4.0.8 升级）
 */
public class SpringDataWebProperties {

    private final Pageable pageable = new Pageable();
    private final Sort sort = new Sort();

    public Pageable getPageable() {
        return pageable;
    }

    public Sort getSort() {
        return sort;
    }

    /**
     * 分页参数配置（与 Spring Boot 3.x SpringDataWebProperties.Pageable API 对齐）。
     */
    public static class Pageable {

        private String pageParameter = "page";
        private String sizeParameter = "size";
        private boolean oneIndexedParameters = false;
        private String prefix = "";
        private String qualifierDelimiter = "_";
        private int defaultPageSize = 20;
        private int maxPageSize = 2000;

        public String getPageParameter() {
            return pageParameter;
        }

        public void setPageParameter(String pageParameter) {
            this.pageParameter = pageParameter;
        }

        public String getSizeParameter() {
            return sizeParameter;
        }

        public void setSizeParameter(String sizeParameter) {
            this.sizeParameter = sizeParameter;
        }

        public boolean isOneIndexedParameters() {
            return oneIndexedParameters;
        }

        public void setOneIndexedParameters(boolean oneIndexedParameters) {
            this.oneIndexedParameters = oneIndexedParameters;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getQualifierDelimiter() {
            return qualifierDelimiter;
        }

        public void setQualifierDelimiter(String qualifierDelimiter) {
            this.qualifierDelimiter = qualifierDelimiter;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }
    }

    /**
     * 排序参数配置（与 Spring Boot 3.x SpringDataWebProperties.Sort API 对齐）。
     */
    public static class Sort {

        private String sortParameter = "sort";

        public String getSortParameter() {
            return sortParameter;
        }

        public void setSortParameter(String sortParameter) {
            this.sortParameter = sortParameter;
        }
    }
}