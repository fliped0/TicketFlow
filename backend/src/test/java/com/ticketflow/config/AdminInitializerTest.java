package com.ticketflow.config;

import com.ticketflow.mapper.UserMapper;
import com.ticketflow.model.entity.UserAccount;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminInitializerTest {
    @Test void emptyConfigurationCreatesNothing() {
        var users = mock(UserMapper.class);
        new AdminInitializer(users, mock(PasswordEncoder.class), "", "").run(null);
        verifyNoInteractions(users);
    }

    @Test void existingUserIsNeverPromotedOrReset() {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.byUsername("admin")).thenReturn(new UserAccount(1L, "admin", "old-hash", "USER", true));
        new AdminInitializer(users, encoder, "ADMIN", "TemporaryPass").run(null);
        verify(users, never()).insert(anyString(), anyString(), anyString());
        verifyNoInteractions(encoder);
    }

    @Test void configuredNewAccountIsEncodedAndCreatedAsAdmin() {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode("TemporaryPass")).thenReturn("encoded");
        new AdminInitializer(users, encoder, "ADMIN", "TemporaryPass").run(null);
        verify(users).insert("admin", "encoded", "ADMIN");
    }

    @Test void concurrentAccountCreationDoesNotResetTheWinningAccount() {
        var users = mock(UserMapper.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode("TemporaryPass")).thenReturn("encoded");
        when(users.insert("admin", "encoded", "ADMIN")).thenThrow(new DuplicateKeyException("concurrent"));
        assertDoesNotThrow(() -> new AdminInitializer(users, encoder, "ADMIN", "TemporaryPass").run(null));
        verify(users, times(1)).insert("admin", "encoded", "ADMIN");
    }
}
