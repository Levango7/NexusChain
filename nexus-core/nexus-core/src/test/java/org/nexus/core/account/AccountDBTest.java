package org.nexus.core.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账户读取的故障语义：没有行 != 数据库不可用。
 */
public class AccountDBTest {

    @Test
    void selectaccountReturnsNullWhenNoRowExists() {
        JdbcTemplate tmpl = mock(JdbcTemplate.class);
        when(tmpl.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        AccountDB accountDB = accountDB(tmpl);

        assertNull(accountDB.selectaccount(new byte[20]));
    }

    @Test
    void selectaccountThrowsWhenDatabaseIsUnavailable() {
        JdbcTemplate tmpl = mock(JdbcTemplate.class);
        when(tmpl.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenThrow(new CannotGetJdbcConnectionException("database down"));

        AccountDB accountDB = accountDB(tmpl);
        AccountStoreHealth health = mock(AccountStoreHealth.class);
        ReflectionTestUtils.setField(accountDB, "accountStoreHealth", health);

        AccountStoreUnavailableException error = assertThrows(
                AccountStoreUnavailableException.class,
                () -> accountDB.selectaccount(new byte[20]));
        assertEquals("AccountDB.selectaccount failed", error.getMessage());
        verify(health).recordFailure(any(RuntimeException.class));
    }

    @Test
    void hasAccountReturnsEmptyWhenNoRowExists() {
        JdbcTemplate tmpl = mock(JdbcTemplate.class);
        when(tmpl.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        AccountDB accountDB = accountDB(tmpl);

        assertEquals(Optional.empty(), accountDB.hasAccount(new byte[20]));
    }

    @Test
    void hasAccountThrowsWhenDatabaseIsUnavailable() {
        JdbcTemplate tmpl = mock(JdbcTemplate.class);
        when(tmpl.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenThrow(new CannotGetJdbcConnectionException("database down"));

        AccountDB accountDB = accountDB(tmpl);

        assertThrows(AccountStoreUnavailableException.class,
                () -> accountDB.hasAccount(new byte[20]));
    }

    private static AccountDB accountDB(JdbcTemplate tmpl) {
        AccountDB accountDB = new AccountDB();
        ReflectionTestUtils.setField(accountDB, "tmpl", tmpl);
        return accountDB;
    }
}
