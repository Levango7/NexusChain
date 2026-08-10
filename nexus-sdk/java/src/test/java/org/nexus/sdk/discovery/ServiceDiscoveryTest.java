package org.nexus.sdk.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServiceInstance 与 InProcessServiceDiscovery 单元测试。
 */
class ServiceDiscoveryTest {

    @Test
    void serviceInstance_getters_shouldReturnAllFields() {
        ServiceInstance inst = new ServiceInstance("id-1", "signing-svc", "localhost", 8081, false);

        assertEquals("id-1", inst.getInstanceId());
        assertEquals("signing-svc", inst.getServiceName());
        assertEquals("localhost", inst.getHost());
        assertEquals(8081, inst.getPort());
        assertFalse(inst.isSecure());
    }

    @Test
    void serviceInstance_baseUrl_http_shouldUseHttpScheme() {
        ServiceInstance inst = new ServiceInstance("id", "svc", "host", 8080, false);

        assertEquals("http://host:8080", inst.baseUrl());
    }

    @Test
    void serviceInstance_baseUrl_https_shouldUseHttpsScheme() {
        ServiceInstance inst = new ServiceInstance("id", "svc", "host", 443, true);

        assertEquals("https://host:443", inst.baseUrl());
    }

    @Test
    void serviceInstance_toString_shouldContainServiceNameAndUrl() {
        ServiceInstance inst = new ServiceInstance("id-1", "my-svc", "host", 8080, false);
        String str = inst.toString();

        assertNotNull(str);
        assertTrue(str.contains("my-svc"));
        assertTrue(str.contains("http://host:8080"));
        assertTrue(str.contains("id-1"));
    }

    @Test
    void inProcessServiceDiscovery_discoverOne_shouldReturnNull() {
        InProcessServiceDiscovery discovery = new InProcessServiceDiscovery();

        assertNull(discovery.discoverOne("any-service"));
    }

    @Test
    void inProcessServiceDiscovery_discoverAll_shouldReturnEmptyList() {
        InProcessServiceDiscovery discovery = new InProcessServiceDiscovery();
        List<ServiceInstance> instances = discovery.discoverAll("any-service");

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    void inProcessServiceDiscovery_isEnabled_shouldReturnFalse() {
        InProcessServiceDiscovery discovery = new InProcessServiceDiscovery();

        assertFalse(discovery.isEnabled());
    }
}