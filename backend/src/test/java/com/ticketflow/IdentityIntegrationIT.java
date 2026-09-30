package com.ticketflow;

import com.ticketflow.mapper.UserMapper;
import com.ticketflow.mapper.CatalogMapper;
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
    @Autowired CatalogMapper catalog;
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

    String adminToken() throws Exception {
        String user = username(), id = register(user, password);
        db.update("UPDATE tf_user SET role='ADMIN' WHERE id=?", Long.parseLong(id));
        return login(user, password);
    }
    JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return body(response).path("data");
    }
    String createCatalogEvent(String admin, String name) throws Exception {
        return data(request("POST","/api/v1/admin/events",Map.of("name",name,"description","A show","category","music","city","Beijing","venue","Hall"),admin),201).path("id").asString();
    }
    Map<String,Object> times(int saleHours, int endHours, int startHours) {
        Instant base=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return Map.of("saleStartAt",base.plusSeconds(saleHours*3600L).toString(),"saleEndAt",base.plusSeconds(endHours*3600L).toString(),"startsAt",base.plusSeconds(startHours*3600L).toString());
    }
    String createCatalogSession(String admin,String eventId,Map<String,Object> times) throws Exception {
        return data(request("POST","/api/v1/admin/events/"+eventId+"/sessions",times,admin),201).path("id").asString();
    }
    String createCatalogTier(String admin,String sessionId,int capacity) throws Exception {
        return data(request("POST","/api/v1/admin/sessions/"+sessionId+"/tiers",Map.of("name","Standard","priceFen",58000,"capacity",capacity),admin),201).path("id").asString();
    }
    long eventVersion(String admin,String id) throws Exception {
        return data(request("GET","/api/v1/admin/events/"+id,null,admin),200).path("version").asLong();
    }

    @Test void catalogPublishRequiresCompleteHierarchyAndPublicVisibility() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"Show_"+UUID.randomUUID());
        assertEquals(404,request("GET","/api/v1/events/"+event,null,null).statusCode());
        assertEquals("INCOMPLETE_CATALOG",body(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin)).path("code").asString());
        String session=createCatalogSession(admin,event,times(1,2,3));
        assertEquals(409,request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin).statusCode());
        String tier=createCatalogTier(admin,session,0);
        assertEquals(0,db.queryForObject("SELECT available FROM tf_stock WHERE tier_id=?",Integer.class,Long.parseLong(tier)));
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin),200);
        assertEquals("ON_SALE",data(request("GET","/api/v1/events/"+event,null,null),200).path("status").asString());
        assertEquals("SALE_NOT_STARTED",data(request("GET","/api/v1/events/"+event+"/sessions",null,null),200).path("items").get(0).path("saleStatus").asString());
        assertEquals(409,request("POST","/api/v1/admin/events/"+event+"/sessions",times(1,2,3),admin).statusCode());
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","OFF_SALE","expectedVersion",1),admin),200);
        assertEquals(404,request("GET","/api/v1/events/"+event+"/sessions",null,null).statusCode());
        assertEquals(404,request("GET","/api/v1/sessions/"+session+"/tiers",null,null).statusCode());
        createCatalogSession(admin,event,times(2,3,4));
        assertEquals(409,request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",2),admin).statusCode());
        String extra=db.queryForObject("SELECT id FROM tf_session WHERE event_id=? ORDER BY id DESC LIMIT 1",Long.class,Long.parseLong(event)).toString();
        createCatalogTier(admin,extra,1);
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",2),admin),200);
        assertEquals(2,data(request("GET","/api/v1/events/"+event+"/sessions",null,null),200).path("total").asInt());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM tf_stock WHERE tier_id IN (SELECT id FROM tf_tier WHERE session_id IN (SELECT id FROM tf_session WHERE event_id=?))",Integer.class,Long.parseLong(event)));
    }

    @Test void catalogPermissionsVersionReplayAndAudit() throws Exception {
        String ordinary=login(usernameForCatalog(),password), admin=adminToken();
        assertEquals(403,request("POST","/api/v1/admin/events",Map.of(),ordinary).statusCode());
        assertEquals(401,request("GET","/api/v1/admin/events",null,null).statusCode());
        String event=createCatalogEvent(admin,"Version_"+UUID.randomUUID());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tf_audit WHERE object_type='EVENT' AND object_id=?",Integer.class,Long.parseLong(event)));
        var update=Map.of("name","Updated","description","A","category","music","city","Beijing","venue","Hall","expectedVersion",0);
        data(request("PUT","/api/v1/admin/events/"+event,update,admin),200);
        assertEquals("VERSION_CONFLICT",body(request("PUT","/api/v1/admin/events/"+event,update,admin)).path("code").asString());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM tf_audit WHERE object_type='EVENT' AND object_id=?",Integer.class,Long.parseLong(event)));
        String session=createCatalogSession(admin,event,times(1,2,3));
        createCatalogTier(admin,session,2);
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",1),admin),200);
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin),200);
        assertEquals(2,eventVersion(admin,event));
    }
    String usernameForCatalog() throws Exception { String user=username(); register(user,password); return user; }

    @Test void catalogSearchPaginationAndLiteralWildcards() throws Exception {
        String admin=adminToken(), marker=UUID.randomUUID().toString().substring(0,8);
        String one=createCatalogEvent(admin,"A%_"+marker);
        String two=createCatalogEvent(admin,"Axx"+marker);
        for (String id:List.of(one,two)) {
            String session=createCatalogSession(admin,id,times(1,2,3)); createCatalogTier(admin,session,1);
            data(request("PUT","/api/v1/admin/events/"+id+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin),200);
        }
        var found=data(request("GET","/api/v1/events?keyword=%25_"+marker+"&city=Beijing&category=music&page=1&size=1",null,null),200);
        assertEquals(1,found.path("total").asInt());
        assertEquals(one,found.path("items").get(0).path("id").asString());
        assertEquals(0,data(request("GET","/api/v1/events?keyword=NotFound"+marker,null,null),200).path("items").size());
        assertEquals(400,request("GET","/api/v1/events?page=0",null,null).statusCode());
        assertEquals(400,request("GET","/api/v1/events?size=101",null,null).statusCode());
        assertEquals(2,data(request("GET","/api/v1/admin/events?status=ON_SALE&keyword="+marker,null,admin),200).path("total").asInt());
    }

    @Test void catalogFrozenTimeCannotMoveForwardAndStockUpdateIsAtomic() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"Frozen_"+UUID.randomUUID());
        Map<String,Object> initial=times(1,2,3);
        String session=createCatalogSession(admin,event,initial), tier=createCatalogTier(admin,session,1);
        String originalFreeze=db.queryForObject("SELECT CAST(freeze_at AS CHAR) FROM tf_session WHERE id=?",String.class,Long.parseLong(session));
        Map<String,Object> later=times(2,3,4);
        data(request("PUT","/api/v1/admin/sessions/"+session,Map.of("startsAt",later.get("startsAt"),"saleStartAt",later.get("saleStartAt"),"saleEndAt",later.get("saleEndAt"),"expectedVersion",0),admin),200);
        assertEquals(originalFreeze,db.queryForObject("SELECT CAST(freeze_at AS CHAR) FROM tf_session WHERE id=?",String.class,Long.parseLong(session)));
        db.update("UPDATE tf_session SET freeze_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",Long.parseLong(session));
        var change=Map.of("name","Changed","priceFen",60000,"capacity",2,"expectedVersion",0);
        assertEquals("CONFIG_FROZEN",body(request("PUT","/api/v1/admin/tiers/"+tier,change,admin)).path("code").asString());
        assertEquals(1,db.queryForObject("SELECT capacity FROM tf_stock WHERE tier_id=?",Integer.class,Long.parseLong(tier)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tf_audit WHERE object_type='TIER' AND object_id=? AND action='UPDATE'",Integer.class,Long.parseLong(tier)));
        assertEquals("CONFIG_FROZEN",body(request("PUT","/api/v1/admin/events/"+event,Map.of("name","New","description","A","category","music","city","Shanghai","venue","Hall","expectedVersion",0),admin)).path("code").asString());
        assertEquals("New Copy",data(request("PUT","/api/v1/admin/events/"+event,Map.of("name","New Copy","description","A","category","music","city","Beijing","venue","Hall","expectedVersion",0),admin),200).path("name").asString());
    }

    @Test void catalogSaleStatusAndCapacityUpdateFollowDatabaseFacts() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"Stock_"+UUID.randomUUID());
        String session=createCatalogSession(admin,event,times(1,2,3)), tier=createCatalogTier(admin,session,0);
        data(request("PUT","/api/v1/admin/tiers/"+tier,Map.of("name","Standard","priceFen",58000,"capacity",4,"expectedVersion",0),admin),200);
        assertEquals(4,db.queryForObject("SELECT available FROM tf_stock WHERE tier_id=?",Integer.class,Long.parseLong(tier)));
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","ON_SALE","expectedVersion",0),admin),200);
        db.update("UPDATE tf_session SET freeze_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,sale_start_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",Long.parseLong(session));
        assertEquals("ON_SALE",data(request("GET","/api/v1/sessions/"+session+"/tiers",null,null),200).path("items").get(0).path("saleStatus").asString());
        db.update("UPDATE tf_stock SET available=0,reserved=4 WHERE tier_id=?",Long.parseLong(tier));
        assertEquals("SOLD_OUT",data(request("GET","/api/v1/events/"+event+"/sessions",null,null),200).path("items").get(0).path("saleStatus").asString());
        assertEquals("SOLD_OUT",data(request("GET","/api/v1/sessions/"+session+"/tiers",null,null),200).path("items").get(0).path("saleStatus").asString());
        assertEquals("CONFIG_FROZEN",body(request("PUT","/api/v1/admin/tiers/"+tier,Map.of("name","Standard","priceFen",58000,"capacity",5,"expectedVersion",1),admin)).path("code").asString());
        db.update("UPDATE tf_session SET sale_end_at=UTC_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE id=?",Long.parseLong(session));
        assertEquals("SALE_ENDED",data(request("GET","/api/v1/events/"+event+"/sessions",null,null),200).path("items").get(0).path("saleStatus").asString());
        data(request("PUT","/api/v1/admin/events/"+event+"/status",Map.of("status","OFF_SALE","expectedVersion",1),admin),200);
        assertEquals("NOT_ON_SALE",data(request("GET","/api/v1/admin/events/"+event+"/sessions",null,admin),200).path("items").get(0).path("saleStatus").asString());
    }

    @Test void catalogStockAndAuditRollbackTogether() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"Rollback_"+UUID.randomUUID());
        String session=createCatalogSession(admin,event,times(1,2,3));
        long sessionId=Long.parseLong(session);
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        assertThrows(DataAccessException.class,()->tx.executeWithoutResult(status->{
            long tier=catalog.createTier(sessionId,"Atomic",500,1);
            catalog.audit(Long.MAX_VALUE,"CREATE","TIER",tier,null,"{}","test-trace");
        }));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tf_tier WHERE session_id=?",Integer.class,sessionId));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tf_stock s JOIN tf_tier t ON t.id=s.tier_id WHERE t.session_id=?",Integer.class,sessionId));
    }

    @Test void catalogRejectsMalformedFieldsAndKeepsPublicQueriesOpen() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"Fields_"+UUID.randomUUID());
        assertEquals(400,request("POST","/api/v1/admin/events/"+event+"/sessions",Map.of("saleStartAt","2026-10-01T10:00:00","saleEndAt","2026-10-01T11:00:00Z","startsAt","2026-10-01T12:00:00Z"),admin).statusCode());
        assertEquals(400,request("POST","/api/v1/admin/events/"+event+"/sessions",Map.of("saleStartAt","2026-10-01T10:00:00Z","saleEndAt","2026-10-01T11:00:00Z","startsAt","2026-10-01T12:00:00Z","unexpected",true),admin).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tf_session WHERE event_id=?",Integer.class,Long.parseLong(event)));
        assertEquals(200,request("GET","/api/v1/events",null,null).statusCode());
        assertEquals(200,request("GET","/api/v1/admin/events/"+event+"/sessions",null,admin).statusCode());
    }

    @Test void catalogHistoryPreventsCapacityResetEvenWithZeroBalances() throws Exception {
        String admin=adminToken(), event=createCatalogEvent(admin,"History_"+UUID.randomUUID());
        String session=createCatalogSession(admin,event,times(1,2,3)), tier=createCatalogTier(admin,session,1);
        long owner=Long.parseLong(register(username(),password));
        db.update("INSERT INTO tf_order(user_id,session_id,tier_id,status,quantity,unit_price_fen,amount_fen,snapshot,starts_at,expires_at,created_at,updated_at) SELECT ?,s.id,t.id,'CANCELLED',1,58000,58000,'{}',s.starts_at,UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM tf_tier t JOIN tf_session s ON s.id=t.session_id WHERE t.id=?",owner,Long.parseLong(tier));
        var update=Map.of("name","Standard","priceFen",58000,"capacity",2,"expectedVersion",0);
        assertEquals("CONFIG_FROZEN",body(request("PUT","/api/v1/admin/tiers/"+tier,update,admin)).path("code").asString());
        assertEquals(1,db.queryForObject("SELECT capacity FROM tf_stock WHERE tier_id=?",Integer.class,Long.parseLong(tier)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tf_audit WHERE object_type='TIER' AND object_id=? AND action='UPDATE'",Integer.class,Long.parseLong(tier)));
    }
}
