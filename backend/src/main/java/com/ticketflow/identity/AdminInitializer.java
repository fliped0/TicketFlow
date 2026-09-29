package com.ticketflow.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements ApplicationRunner {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public AdminInitializer(UserMapper users, PasswordEncoder passwords,
            @Value("${ticketflow.admin.username:}") String username,
            @Value("${ticketflow.admin.password:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    public void run(ApplicationArguments args) {
        if (username.isEmpty() && password.isEmpty()) return;
        String normalized = AccountRules.username(username);
        AccountRules.password(password);
        if (users.byUsername(normalized) != null) return;
        try {
            users.insert(normalized, passwords.encode(password), "ADMIN");
        } catch (DuplicateKeyException e) {
            // Another instance may have created the account. Never promote or reset an existing user.
        }
    }
}
