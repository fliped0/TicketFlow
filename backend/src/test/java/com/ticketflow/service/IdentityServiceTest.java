package com.ticketflow.service;

import com.ticketflow.common.exception.BusinessException;
import com.ticketflow.mapper.UserMapper;
import com.ticketflow.model.entity.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityServiceTest {
    private UserMapper users;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        service = new IdentityService(users, mock(PasswordEncoder.class), mock(JwtEncoder.class));
    }

    @Test
    void currentUserUsesDatabaseRoleAndReturnsPublicData() {
        when(users.byId(7L)).thenReturn(new UserAccount(7L, "alice", "private-hash", "ADMIN", true));
        var result = service.getCurrentUser(7L);
        assertEquals("7", result.userId());
        assertEquals("alice", result.username());
        assertEquals("ADMIN", result.role());
    }

    @Test
    void missingUserIsRejectedAfterEarlierAuthentication() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.getCurrentUser(7L));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
    }

    @Test
    void disabledUserIsRejectedAfterEarlierAuthentication() {
        when(users.byId(7L)).thenReturn(new UserAccount(7L, "alice", "private-hash", "USER", false));
        BusinessException error = assertThrows(BusinessException.class, () -> service.getCurrentUser(7L));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
    }
}
