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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 *   <li>{@code store-type=jpa} + Repository 缺失 → <b>fail-closed 抛错</b>（#32 改：旧行为是
 *       WARN + 静默回退内存，等于把 prod 多副本审批不可见的成因藏进一条日志）</li>
 *   <li>{@code store-type=""}(空) + legacy use-database=true + Repository 可用 → jpa（旧开关语义映射）</li>
 *   <li>{@code store-type=bogus}（未知值）→ <b>fail-closed 抛错</b>；显式 {@code memory} 才是内存</li>
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
    @DisplayName("store-type 留空 + legacy use-database=true + Repository 可用 → jpa（旧开关语义保留）")
    void emptyStoreTypeWithLegacyUseDatabaseTrueSelectsJpaStore() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(mockRepository()), "", true,
                tempDir.resolve("approval-legacy.jsonl").toString());

        assertInstanceOf(JpaApprovalStore.class, selectedStore(service));
    }

    @Test
    @DisplayName("store-type=jpa 但 Repository 缺失 → fail-closed 抛错（不再静默回退内存）")
    void autowiredConstructorStoreTypeJpaWithoutRepositoryFailsClosed() {
        // 旧行为：WARN + 回退 MapApprovalStore —— 一次 datasource/依赖配错就把"共享"悄悄
        // 降级成"每副本各一份内存"，正是 prod 3 副本审批不可见的成因；服务还照样健康启动，
        // 运维无从察觉。与同子系统 JpaApprovalStore 自陈的 fail-fast 原则相反，故改为拒绝启动。
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new SigningApprovalService(
                        policy(), auditLog(), providerReturning(null), "jpa", false,
                        tempDir.resolve("approval-fallback.jsonl").toString()));

        assertTrue(ex.getMessage().contains("store-type=jpa"),
                "异常须点明是 jpa 选择失败，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("SigningApprovalRequestRepository"),
                "异常须指名缺哪个 bean，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("遗留开关 use-database=true 但 Repository 缺失 → 同样 fail-closed")
    void legacyUseDatabaseTrueWithoutRepositoryFailsClosed() {
        // store-type 留空时 use-database=true 也解析成 jpa，必须走同一条 fail-closed 分支，
        // 否则旧开关路径仍保留静默降级。
        assertThrows(IllegalStateException.class, () -> new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "", true,
                tempDir.resolve("approval-legacy-fallback.jsonl").toString()));
    }

    @Test
    @DisplayName("store-type=bogus（未知值）→ fail-closed 抛错（显式写错不得静默变成内存存储）")
    void unknownStoreTypeFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "bogus", false,
                tempDir.resolve("approval-bogus.jsonl").toString()));
    }

    @Test
    @DisplayName("store-type=memory 显式声明 → 仍是内存存储（单实例部署的合法选择，不受影响）")
    void explicitMemoryStoreTypeStillMemory() {
        SigningApprovalService service = new SigningApprovalService(
                policy(), auditLog(), providerReturning(null), "memory", false,
                tempDir.resolve("approval-memory.jsonl").toString());

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