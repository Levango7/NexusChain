package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.model.BridgePauseRecord;
import org.nexus.bridge.repository.BridgePauseRecordRepository;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultEmergencyPauseService} 单元测试：覆盖暂停/恢复/紧停/状态查询。
 */
@ExtendWith(MockitoExtension.class)
class DefaultEmergencyPauseServiceTest {

    @Mock
    private BridgePauseRecordRepository repository;

    private DefaultEmergencyPauseService service;

    @BeforeEach
    void setUp() {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        service = new DefaultEmergencyPauseService(repository);
    }

    @Test
    @DisplayName("构造时从 DB 加载记录到缓存")
    void constructor_loadsFromDb() {
        BridgePauseRecord record = new BridgePauseRecord("bridge-1", "PAUSED", "reason", "v1");
        when(repository.findAll()).thenReturn(java.util.Arrays.asList(record));

        DefaultEmergencyPauseService svc = new DefaultEmergencyPauseService(repository);

        assertTrue(svc.isPaused("bridge-1"));
        assertEquals("PAUSED", svc.getBridgeState("bridge-1"));
        assertEquals("reason", svc.getPauseReason("bridge-1"));
    }

    @Test
    @DisplayName("构造时 DB 异常应被捕获，不抛出")
    void constructor_dbExceptionHandled() {
        when(repository.findAll()).thenThrow(new RuntimeException("DB not ready"));
        assertDoesNotThrow(() -> new DefaultEmergencyPauseService(repository));
    }

    @Test
    @DisplayName("pauseBridge: 应将状态置为 PAUSED")
    void pauseBridge_setsPausedState() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.pauseBridge("bridge-1");

        assertTrue(service.isPaused("bridge-1"));
        assertFalse(service.isEmergencyStopped("bridge-1"));
        assertEquals("PAUSED", service.getBridgeState("bridge-1"));
        assertEquals("manual pause", service.getPauseReason("bridge-1"));
    }

    @Test
    @DisplayName("pauseBridge: 空 bridgeId 应抛 IllegalArgumentException")
    void pauseBridge_emptyIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.pauseBridge(null));
        assertThrows(IllegalArgumentException.class, () -> service.pauseBridge(""));
    }

    @Test
    @DisplayName("resumeBridge: 应将状态恢复为 ACTIVE")
    void resumeBridge_setsActiveState() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 先暂停
        service.pauseBridge("bridge-1");
        assertTrue(service.isPaused("bridge-1"));

        // 恢复
        service.resumeBridge("bridge-1");
        assertFalse(service.isPaused("bridge-1"));
        assertEquals("ACTIVE", service.getBridgeState("bridge-1"));
        assertNull(service.getPauseReason("bridge-1"));
    }

    @Test
    @DisplayName("resumeBridge: 空 bridgeId 应抛 IllegalArgumentException")
    void resumeBridge_emptyIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.resumeBridge(null));
        assertThrows(IllegalArgumentException.class, () -> service.resumeBridge(""));
    }

    @Test
    @DisplayName("triggerPause: EMERGENCY_STOP 状态")
    void triggerPause_emergencyStop() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.triggerPause("bridge-1", "EMERGENCY_STOP", "key leak", "validator-1");

        assertTrue(service.isPaused("bridge-1"));
        assertTrue(service.isEmergencyStopped("bridge-1"));
        assertEquals("EMERGENCY_STOP", service.getBridgeState("bridge-1"));
        assertEquals("key leak", service.getPauseReason("bridge-1"));
    }

    @Test
    @DisplayName("triggerPause: 非法状态应抛 IllegalArgumentException")
    void triggerPause_invalidStateThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.triggerPause("bridge-1", "ACTIVE", "reason", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.triggerPause("bridge-1", "UNKNOWN", "reason", "v1"));
    }

    @Test
    @DisplayName("triggerPause: 空 bridgeId 应抛 IllegalArgumentException")
    void triggerPause_emptyIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.triggerPause(null, "PAUSED", "reason", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.triggerPause("", "PAUSED", "reason", "v1"));
    }

    @Test
    @DisplayName("triggerPause: null reason 不缓存原因")
    void triggerPause_nullReasonNotCached() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.triggerPause("bridge-1", "PAUSED", null, "v1");

        assertTrue(service.isPaused("bridge-1"));
        assertNull(service.getPauseReason("bridge-1"));
    }

    @Test
    @DisplayName("isPaused: 未知桥返回 false")
    void isPaused_unknownBridgeReturnsFalse() {
        assertFalse(service.isPaused("unknown"));
    }

    @Test
    @DisplayName("isEmergencyStopped: 仅 EMERGENCY_STOP 返回 true")
    void isEmergencyStopped_onlyEmergencyStop() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.triggerPause("bridge-1", "PAUSED", "reason", "v1");
        assertFalse(service.isEmergencyStopped("bridge-1"));

        service.triggerPause("bridge-1", "EMERGENCY_STOP", "reason", "v1");
        assertTrue(service.isEmergencyStopped("bridge-1"));
    }

    @Test
    @DisplayName("getBridgeState: 未知桥返回 ACTIVE")
    void getBridgeState_unknownReturnsActive() {
        assertEquals("ACTIVE", service.getBridgeState("unknown"));
    }

    @Test
    @DisplayName("getPauseReason: 未知桥返回 null")
    void getPauseReason_unknownReturnsNull() {
        assertNull(service.getPauseReason("unknown"));
    }

    @Test
    @DisplayName("getPossibleStatus: 返回不可变状态映射")
    void getPossibleStatus_returnsUnmodifiableMap() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.pauseBridge("bridge-1");

        Map<String, String> status = service.getPossibleStatus();
        assertEquals("PAUSED", status.get("bridge-1"));
        assertThrows(UnsupportedOperationException.class,
                () -> status.put("bridge-2", "ACTIVE"));
    }

    @Test
    @DisplayName("resumeBridge: 已存在记录时更新而非新建")
    void resumeBridge_updatesExistingRecord() {
        BridgePauseRecord existing = new BridgePauseRecord("bridge-1", "PAUSED", "reason", "v1");
        when(repository.findById("bridge-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resumeBridge("bridge-1");

        assertEquals("ACTIVE", existing.getState());
        assertNull(existing.getReason());
        assertNotNull(existing.getUpdatedAt());
    }
}