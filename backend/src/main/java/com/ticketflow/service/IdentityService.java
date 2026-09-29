package com.ticketflow.service;

import com.ticketflow.mapper.UserMapper;
import com.ticketflow.model.entity.UserAccount;
import com.ticketflow.model.vo.UserVO;
import com.ticketflow.model.vo.TokenVO;

import com.ticketflow.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;

    public IdentityService(UserMapper users, PasswordEncoder passwords, JwtEncoder encoder) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public UserVO register(String username, String password) {
        String normalized = AccountRules.username(username);
        AccountRules.password(password);
        try {
            users.insert(normalized, passwords.encode(password), "USER");
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "USERNAME_EXISTS", "账号已存在");
        }
        UserAccount user = users.byUsername(normalized);
        return new UserVO(user.id().toString(), user.username(), user.role());
    }

    public UserVO getCurrentUser(long userId) {
        UserAccount user = users.byId(userId);
        if (user == null || !user.enabled()) {
            throw new BusinessException(401, "UNAUTHENTICATED", "请先登录");
        }
        return new UserVO(user.id().toString(), user.username(), user.role());
    }
    public TokenVO login(String username, String password) {
        String normalized = AccountRules.username(username);
        AccountRules.password(password);
        UserAccount user = users.byUsername(normalized);
        // Unknown accounts still perform password verification to reduce account enumeration by timing.
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
        if (user == null || !user.enabled() || !matches) {
            throw new BusinessException(401, "BAD_CREDENTIALS", "账号或密码错误");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticketflow").audience(List.of("ticketflow-api")).subject(user.id().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(1800)).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new TokenVO(token, "Bearer", 1800);
    }

}
