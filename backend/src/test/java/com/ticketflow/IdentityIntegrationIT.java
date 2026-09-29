package com.ticketflow;

import com.ticketflow.mapper.UserMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityIntegrationIT {
    static Path testKeys;

    @DynamicPropertySource
    static void configureIsolatedTestResources(DynamicPropertyRegistry properties) throws Exception {
        if (!"ticketflow_test".equals(System.getenv("TF_DB_NAME")) ||
                !"tf_test".equals(System.getenv("TF_DB_USER"))) {
            throw new IllegalStateException("Refusing to start: use ticketflow_test with tf_test before Flyway runs");
        }
        testKeys = Files.createTempDirectory("ticketflow-test-keys-");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        Files.writeString(testKeys.resolve("private.pem"), "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Files.writeString(testKeys.resolve("public.pem"), "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        properties.add("ticketflow.jwt.private-key", () -> testKeys.resolve("private.pem").toString());
        properties.add("ticketflow.jwt.public-key", () -> testKeys.resolve("public.pem").toString());
        properties.add("ticketflow.admin.username", () -> "");
        properties.add("ticketflow.admin.password", () -> "");
    }

    @AfterAll static void removeTemporaryTestKeys() throws Exception {
        if (testKeys != null) {
            Files.deleteIfExists(testKeys.resolve("private.pem"));
            Files.deleteIfExists(testKeys.resolve("public.pem"));
            Files.deleteIfExists(testKeys);
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserMapper users;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final String password = "Test_" + UUID.randomUUID();

    @BeforeEach void requireIsolatedDatabase() {
        assertEquals("ticketflow_test", db.queryForObject("SELECT DATABASE()", String.class),
                "Integration tests must only use ticketflow_test");
    }

    String username() { return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    Map<String, String> credentials(String user, String pass) { return Map.of("username", user, "password", pass); }
    String register(String user, String pass) throws Exception {
        var response = request("POST", "/api/v1/auth/register", credentials(user, pass), null);
        assertEquals(201, response.statusCode(), response.body());
        assertTrue(body(response).path("data").path("userId").isString());
        assertFalse(response.body().contains("password"));
        return body(response).path("data").path("userId").asString();
    }
    String login(String user, String pass) throws Exception {
        var response = request("POST", "/api/v1/auth/login", credentials(user, pass), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1800, body(response).path("data").path("expiresIn").asInt());
        return body(response).path("data").path("accessToken").asString();
    }

    @Test void migrationCreatesTwelveTablesAndIsRepeatable() {
        assertEquals(12, db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'tf_%'",
                Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
    }

    @Test void registrationLoginAndMeUseCurrentDatabaseRole() throws Exception {
        String user = username(), id = register(user.toUpperCase(Locale.ROOT), password);
        String token = login(user, password);
        var me = request("GET", "/api/v1/users/me", null, token);
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(id, body(me).path("data").path("userId").asString());
        assertEquals(user, body(me).path("data").path("username").asString());
        assertEquals("USER", body(me).path("data").path("role").asString());
        assertEquals(me.headers().firstValue("X-Trace-Id").orElseThrow(), body(me).path("traceId").asString());
        db.update("UPDATE tf_user SET role='ADMIN' WHERE id=?", Long.parseLong(id));
        assertEquals("ADMIN", body(request("GET", "/api/v1/users/me", null, token)).path("data").path("role").asString());
        db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?", Long.parseLong(id));
        assertEquals(401, request("GET", "/api/v1/users/me", null, token).statusCode());
    }

    @Test void duplicateAccountsAndConcurrentRegistrationAreRejected() throws Exception {
        String user = username();
        register(user, password);
        var duplicate = request("POST", "/api/v1/auth/register", credentials(user.toUpperCase(Locale.ROOT), password), null);
        assertEquals(409, duplicate.statusCode());
        assertEquals("USERNAME_EXISTS", body(duplicate).path("code").asString());
        String concurrentUser = username();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> { start.await(); return request("POST", "/api/v1/auth/register", credentials(concurrentUser, password), null).statusCode(); };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            assertEquals(Set.of(201, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM tf_user WHERE username=?", Integer.class, concurrentUser));
        } finally { pool.shutdownNow(); }
    }

    @Test void unknownFieldsAndInputBoundsAreRejected() throws Exception {
        for (var invalid : List.of(
                Map.of("username", username(), "password", password, "role", "ADMIN"),
                credentials("abc", password), credentials("a".repeat(33), password),
                credentials(username(), "1234567"), credentials(username(), "😀".repeat(65)))) {
            var response = request("POST", "/api/v1/auth/register", invalid, null);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("VALIDATION_ERROR", body(response).path("code").asString());
        }
        register(username(), "😀".repeat(64));
        register(username(), " ".repeat(8));
    }

    @Test void passwordIsSaltedAndLoginFailureDoesNotRevealAccountExistence() throws Exception {
        String a = username(), b = username();
        register(a, password); register(b, password);
        String hashA = users.byUsername(a).passwordHash(), hashB = users.byUsername(b).passwordHash();
        assertTrue(hashA.startsWith("{pbkdf2}")); assertNotEquals(hashA, hashB);
        assertTrue(passwords.matches(password, hashA)); assertFalse(hashA.contains(password));
        for (String user : List.of(a, username())) {
            var response = request("POST", "/api/v1/auth/login", credentials(user, "WrongPassword"), null);
            assertEquals(401, response.statusCode());
            assertEquals("BAD_CREDENTIALS", body(response).path("code").asString());
        }
    }

    @Test void protectedRoutesRejectMissingInvalidOrInsufficientCredentials() throws Exception {
        assertEquals(401, request("GET", "/api/v1/users/me", null, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/users/me", null, "not.a.token").statusCode());
        String user = username(); register(user, password);
        var denied = request("GET", "/api/v1/admin/events", null, login(user, password));
        assertEquals(403, denied.statusCode());
        assertEquals("FORBIDDEN", body(denied).path("code").asString());
    }

    String signedToken(String subject, String issuer, String audience, Instant expires, Instant notBefore) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(120)).expiresAt(expires).notBefore(notBefore).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    @Test void jwtEnforcesIssuerAudienceExpiryNotBeforeAndSignature() throws Exception {
        String user = username(), id = register(user, password);
        Instant now = Instant.now();
        for (String token : List.of(
                signedToken(id, "wrong", "ticketflow-api", now.plusSeconds(300), now),
                signedToken(id, "ticketflow", "wrong", now.plusSeconds(300), now),
                signedToken(id, "ticketflow", "ticketflow-api", now.minusSeconds(60), now.minusSeconds(120)),
                signedToken(id, "ticketflow", "ticketflow-api", now.plusSeconds(600), now.plusSeconds(120)),
                signedToken("not-a-number", "ticketflow", "ticketflow-api", now.plusSeconds(300), now))) {
            assertEquals(401, request("GET", "/api/v1/users/me", null, token).statusCode());
        }
        String valid = login(user, password);
        String[] parts = valid.split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertEquals(401, request("GET", "/api/v1/users/me", null, tampered).statusCode());
    }

    @Test void healthIsPublicAndDoesNotExposeDetails() throws Exception {
        var response = request("GET", "/actuator/health", null, null);
        assertEquals(200, response.statusCode());
        assertEquals("UP", body(response).path("status").asString());
        assertFalse(body(response).has("components"));
    }

    @Test void schemaEnforcesUniquenessForeignKeysAndChecks() throws Exception {
        String user = username(); register(user, password);
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO tf_user(username,password_hash,role,created_at) VALUES(?,?,'USER',UTC_TIMESTAMP(6))", user, "dummy"));
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO tf_user(username,password_hash,role,created_at) VALUES(?,?,'ROOT',UTC_TIMESTAMP(6))", username(), "dummy"));
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO tf_stock(tier_id,capacity,available,reserved,sold,updated_at) VALUES(9223372036854775807,1,1,0,0,UTC_TIMESTAMP(6))"));
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO tf_event(name,description,category,city,venue,created_at,updated_at) VALUES('fixture','','TEST','City','Venue',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            long event = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            db.update("INSERT INTO tf_session(event_id,starts_at,sale_start_at,sale_end_at,freeze_at,created_at,updated_at) VALUES(?,UTC_TIMESTAMP(6)+INTERVAL 3 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 2 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", event);
            long session = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            db.update("INSERT INTO tf_tier(session_id,name,price_fen,created_at,updated_at) VALUES(?,'tier',58000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", session);
            long tier = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            assertThrows(DataAccessException.class, () -> db.update(
                    "INSERT INTO tf_stock(tier_id,capacity,available,reserved,sold,updated_at) VALUES(?,1,-1,2,0,UTC_TIMESTAMP(6))", tier));
            assertThrows(DataAccessException.class, () -> db.update(
                    "INSERT INTO tf_stock(tier_id,capacity,available,reserved,sold,updated_at) VALUES(?,1,2,0,0,UTC_TIMESTAMP(6))", tier));
            status.setRollbackOnly();
        });
    }

    @Test void savepointRollsBackBusinessChangesWithoutLosingRequestResult() throws Exception {
        String user = username(), id = register(user, password), key = UUID.randomUUID().toString();
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM tf_user WHERE id=? FOR UPDATE", Long.class, Long.parseLong(id));
            db.update("INSERT INTO tf_request(user_id,operation,request_key,payload_hash,state,created_at) VALUES(?,'CREATE',?,?,'PROCESSING',UTC_TIMESTAMP(6))", Long.parseLong(id), key, "0".repeat(64));
            Object savepoint = status.createSavepoint();
            db.update("UPDATE tf_user SET enabled=FALSE WHERE id=?", Long.parseLong(id));
            status.rollbackToSavepoint(savepoint);
            status.releaseSavepoint(savepoint);
            db.update("UPDATE tf_request SET state='REJECTED',http_status=409,result_code='SOLD_OUT',result_json='{}',completed_at=UTC_TIMESTAMP(6) WHERE user_id=? AND request_key=?", Long.parseLong(id), key);
        });
        assertTrue(users.byId(Long.parseLong(id)).enabled());
        assertEquals("REJECTED", db.queryForObject("SELECT state FROM tf_request WHERE user_id=? AND request_key=?", String.class, Long.parseLong(id), key));
    }
}
