package com.ticketflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

/** Real refused TCP connection, not a mocked Redis exception or a cloud restart. */
@EnabledIfEnvironmentVariable(named="TF_REDIS_IT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ticketflow.redis.enabled=true", "ticketflow.redis.namespace=tf:test:outage",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms"})
class RedisOutageIT extends OrderTestSupport {
    @Test void refusedRedisFallsBackAndSyncTradeStillEnforcesDatabaseRules() throws Exception {
        var f=fixture(1); var user=actor(false); String key=key();
        assertEquals(200,request("GET","/api/v1/events/"+f.event(),null,null,null).statusCode());
        data(create(user,f.tier(),key),201);
        var replay=create(user,f.tier(),key); data(replay,201); assertTrue(body(replay).path("replayed").asBoolean());
        rejected(create(user,f.otherTier(),key()),"PURCHASE_LIMIT");
        var other=actor(false); rejected(create(other,f.tier(),key()),"SOLD_OUT");
        consistent(f,1);
    }
}
