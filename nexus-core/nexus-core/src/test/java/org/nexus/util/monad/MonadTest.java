package org.nexus.util.monad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Monad} 单元测试。
 */
class MonadTest {

    @Test
    void ofNonNullIsPresent() throws Exception {
        Monad<String, Exception> m = Monad.of("hello");
        assertTrue(m.isPresent());
        assertEquals("hello", m.get());
    }

    @Test
    void ofNullIsNotPresent() {
        Monad<String, Exception> m = Monad.of(null);
        assertFalse(m.isPresent());
    }

    @Test
    void emptyIsNotPresent() {
        Monad<String, Exception> m = Monad.empty(String.class);
        assertFalse(m.isPresent());
    }

    @Test
    void mapTransformsValue() throws Exception {
        Monad<Integer, Exception> m = Monad.of(5);
        Monad<Integer, Exception> mapped = m.map(x -> x * 2);
        assertEquals(10, mapped.get());
    }

    @Test
    void mapOnEmptyPropagatesError() {
        Monad<String, Exception> m = Monad.of(null);
        Monad<Integer, Exception> mapped = m.map(x -> 1);
        assertFalse(mapped.isPresent());
    }

    @Test
    void supplyReturnsValue() throws Exception {
        Monad<Integer, Exception> m = Monad.supply(() -> 42);
        assertTrue(m.isPresent());
        assertEquals(42, m.get());
    }

    @Test
    void supplyThrowingCapturesError() {
        Monad<Integer, Exception> m = Monad.supply(() -> {
            throw new RuntimeException("boom");
        });
        assertFalse(m.isPresent());
    }

    @Test
    void orElseReturnsDataWhenPresent() {
        Monad<String, Exception> m = Monad.of("value");
        assertEquals("value", m.orElse("default"));
    }

    @Test
    void orElseReturnsDefaultWhenError() {
        Monad<String, Exception> m = Monad.of(null);
        assertEquals("default", m.orElse("default"));
    }

    @Test
    void orElseGetReturnsDataWhenPresent() {
        Monad<String, Exception> m = Monad.of("v");
        assertEquals("v", m.orElseGet(() -> "default"));
    }

    @Test
    void orElseGetReturnsSuppliedWhenError() {
        Monad<String, Exception> m = Monad.of(null);
        assertEquals("supplied", m.orElseGet(() -> "supplied"));
    }

    @Test
    void getThrowsWhenError() {
        Monad<String, Exception> m = Monad.of(null);
        assertThrows(Exception.class, m::get);
    }

    @Test
    void filterPassingPredicateKeepsValue() throws Exception {
        Monad<Integer, Exception> m = Monad.of(10);
        Monad<Integer, Exception> filtered = m.filter(x -> x > 5);
        assertTrue(filtered.isPresent());
        assertEquals(10, filtered.get());
    }

    @Test
    void filterFailingPredicateProducesError() {
        Monad<Integer, Exception> m = Monad.of(10);
        Monad<Integer, Exception> filtered = m.filter(x -> x > 100);
        assertFalse(filtered.isPresent());
    }

    @Test
    void ifPresentInvokesConsumer() throws Exception {
        Monad<Integer, Exception> m = Monad.of(5);
        final int[] holder = {0};
        Monad<Integer, Exception> r = m.ifPresent(x -> holder[0] = x);
        assertEquals(5, holder[0]);
        assertTrue(r.isPresent());
    }

    @Test
    void flatMapChainsMonads() throws Exception {
        Monad<Integer, Exception> m = Monad.of(3);
        Monad<Integer, Exception> flat = m.flatMap(x -> Monad.of(x * 10));
        assertEquals(30, flat.get());
    }

    @Test
    void composeCombinesTwoMonads() throws Exception {
        Monad<Integer, Exception> a = Monad.of(3);
        Monad<Integer, Exception> b = Monad.of(4);
        Monad<Integer, Exception> c = a.compose(b, (x, y) -> x + y);
        assertEquals(7, c.get());
    }

    @Test
    void orElseOfReplacesError() throws Exception {
        Monad<String, Exception> m = Monad.of(null);
        Monad<String, Exception> r = m.orElseOf("fallback");
        assertTrue(r.isPresent());
        assertEquals("fallback", r.get());
    }

    @Test
    void orElseOfKeepsValueWhenPresent() throws Exception {
        Monad<String, Exception> m = Monad.of("v");
        Monad<String, Exception> r = m.orElseOf("fallback");
        assertEquals("v", r.get());
    }

    @Test
    void exceptInvokesConsumerOnError() {
        Monad<String, Exception> m = Monad.of(null);
        final boolean[] called = {false};
        m.except(e -> called[0] = true);
        assertTrue(called[0]);
    }

    @Test
    void exceptDoesNotInvokeWhenPresent() {
        Monad<String, Exception> m = Monad.of("v");
        m.except(e -> fail("should not be called"));
    }

    @Test
    void orElseThrowThrowsWhenError() {
        Monad<String, Exception> m = Monad.of(null);
        assertThrows(RuntimeException.class, () -> m.orElseThrow(new RuntimeException("x")));
    }

    @Test
    void orElseThrowReturnsWhenPresent() throws Exception {
        Monad<String, Exception> m = Monad.of("v");
        assertEquals("v", m.orElseThrow(new RuntimeException("x")));
    }

    @Test
    void onCleanAndCleanUp() throws Exception {
        Monad<Integer, Exception> m = Monad.of(5);
        final int[] counter = {0};
        m.onClean(x -> counter[0]++);
        m.cleanUp();
        assertEquals(1, counter[0]);
    }
}