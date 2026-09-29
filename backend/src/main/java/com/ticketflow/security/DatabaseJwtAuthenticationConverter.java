package com.ticketflow.security;

import com.ticketflow.mapper.UserMapper;
import com.ticketflow.model.entity.UserAccount;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves current account state after JWT signature and claims validation. */
@Component
public class DatabaseJwtAuthenticationConverter implements Converter<Jwt, JwtAuthenticationToken> {
    private final UserMapper users;

    public DatabaseJwtAuthenticationConverter(UserMapper users) {
        this.users = users;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt token) {
        UserAccount user;
        try {
            user = users.byId(Long.parseLong(token.getSubject()));
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Invalid subject");
        }
        if (user == null || !user.enabled()) {
            throw new BadCredentialsException("Inactive account");
        }
        return new JwtAuthenticationToken(token,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role())), user.id().toString());
    }
}
