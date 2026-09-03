package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;

/**
 * Local username/password accounts backed by the {@code users} table. Verifies
 * credentials with BCrypt; token minting lives in {@code security.JwtIssuer}.
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private static final RowMapper<UserAccount> MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            Optional.ofNullable(rs.getTimestamp("created_at")).map(Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a new account.
     *
     * @throws IllegalStateException if the username is already taken
     */
    public UserAccount register(String username, String rawPassword) {
        String normalized = normalize(username);
        String hash = passwordEncoder.encode(rawPassword);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO users (username, password_hash, role)
                    VALUES (?, ?, 'USER')
                    RETURNING id
                    """, Long.class, normalized, hash);
            log.info("Registered new user '{}' (id={}).", normalized, id);
            return new UserAccount(id != null ? id : -1, normalized, hash, "USER", null);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Username '" + normalized + "' is already taken.");
        }
    }

    /** Returns the account only when the password matches. */
    public Optional<UserAccount> authenticate(String username, String rawPassword) {
        Optional<UserAccount> found = findByUsername(username);
        if (found.isEmpty()) {
            // Spend a hash cycle anyway so response time does not reveal whether
            // the username exists.
            passwordEncoder.encode(rawPassword);
            return Optional.empty();
        }
        UserAccount user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query(
                "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?",
                MAPPER, normalize(username)).stream().findFirst();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.query(
                "SELECT id, username, password_hash, role, created_at FROM users WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * Permanently removes the account row. Callers are responsible for tearing
     * down anything keyed on the id first (owned projects, active session).
     *
     * @return {@code true} if a row was deleted
     */
    public boolean deleteById(long id) {
        int deleted = jdbc.update("DELETE FROM users WHERE id = ?", id);
        if (deleted > 0) {
            log.info("Deleted user account id={}.", id);
        }
        return deleted > 0;
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, normalize(username));
        return count != null && count > 0;
    }

    private static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
