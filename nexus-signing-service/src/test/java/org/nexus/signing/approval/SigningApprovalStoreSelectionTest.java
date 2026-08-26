package org.nexus.signing.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SigningApprovalService} 构造器审批存储选择逻辑单元测试（任务 #378，高#8）。
 *
 * <p>验证私有全参构造器承载的选择逻辑优先级：</p>
 * <ol>
 *   <li>两参简化构造器（无任何开关参数）→ 内存 {@link MapApprovalStore}（默认行为不变）</li>
 *   <li>{@code store-type=file} → {@link FileBasedApprovalStore}</li>
 *   <li>{@code store-type=jpa} + Repository 可用 → {@link JpaApprovalStore}</li>
 *   <li>{@code store-type=jpa} + Repository 缺失 → 回退内存（fail-safe）</li>
 *   <li>{@code store-type=""}(空) + legacy use-database=true → jpa（旧开关语义映射）</li>
 *   <li>{@code store-type=bogus}（未知值）→ 回退内存</li>
 * </ol>
 *
 * <p>选择结果通过反射读取私有字段 {@code requestStore} 断言（构造器的直接赋值目标）；
 * 包级方法 {@code storeInUse()} 应与该字段引用同一实例，另有专门用例交叉验证。</p>
 */
class SigningApprovalStoreSelectionTest {

    /** JUnit 内置临时目录（每个测试方法独立），file 存储路径指向此处避免污染工作目录。 */
    @TempDir
    Path tempDir;

    // --- 用例 ---

    @Test
    @DisplayName("两参构造器：无任何开关配置 → 内存存储（默认行为不变）")
    void twoArgConstructorDefaultsToMemoryStore() {
        SigningApprovalService service = new SigningApprovalService(policy(), auditLog());

        assertInstanceOf(MapApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type=file → FileBasedApprovalStore")
    void autowiredConstructorStoreTypeFileSelectsFileBasedStore() {
        String filePath = tempDir.resolve("approval-selection.jsonl").toString();

        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "file", false, filePath);

        assertInstanceOf(FileBasedApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type=jpa + Repository 可用 → JpaApprovalStore")
    void autowiredConstructorStoreTypeJpaWithRepositorySelectsJpaStore() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(mockRepository()), "jpa", false,
                tempDir.resolve("approval-jpa.jsonl").toString());

        assertInstanceOf(JpaApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type=jpa 但 Repository 缺失 → fail-safe 回退内存存储")
    void autowiredConstructorStoreTypeJpaWithoutRepositoryFallsBackToMemory() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "jpa", false,
                tempDir.resolve("approval-fallback.jsonl").toString());

        assertInstanceOf(MapApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type 留空 + legacy use-database=true → 按旧开关语义选择 jpa")
    void emptyStoreTypeWithLegacyUseDatabaseTrueSelectsJpaStore() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(mockRepository()), "", true,
                tempDir.resolve("approval-legacy.jsonl").toString());

        assertInstanceOf(JpaApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type=bogus（未知值）→ 回退内存存储")
    void unknownStoreTypeFallsBackToMemoryStore() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "bogus", false,
                tempDir.resolve("approval-bogus.jsonl").toString());

        assertInstanceOf(MapApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("包级方法 storeInUse() 与构造器选定的 requestStore 引用同一实例")
    void packageLevelStoreInUseMatchesConstructorChoice() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(mockRepository()), "jpa", false,
                tempDir.resolve("approval-consistency.jsonl").toString());

        assertSame(selectedStore(service), service.storeInUse());
    }

    // --- 辅助 ---

    private static MpcApprovalPolicy policy() {
        return mock(MpcApprovalPolicy.class);
    }

    private static AuditLogService auditLog() {
        return mock(AuditLogService.class);
    }

    private static SigningApprovalRequestRepository mockRepository() {
        return mock(SigningApprovalRequestRepository.class);
    }

    /**
     * 构造 ObjectProvider mock，模拟 Spring 容器对
     * {@link SigningApprovalRequestRepository}（@Autowired(required=false) 语义）的按需解析。
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<SigningApprovalRequestRepository> providerReturning(
            SigningApprovalRequestRepository repository) {
        ObjectProvider<SigningApprovalRequestRepository> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        return provider;
    }

    /** 反射读取私有字段 requestStore——构造器存储选择的直接证据。 */
    private static ApprovalStore selectedStore(SigningApprovalService service) {
        try {
            Field field = SigningApprovalService.class.getDeclaredField("requestStore");
            field.setAccessible(true);
            return (ApprovalStore) field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 SigningApprovalService.requestStore 字段", e);
        }
    }
}