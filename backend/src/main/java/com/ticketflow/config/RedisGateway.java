package com.ticketflow.config;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Network adapter. Services call this only outside database transactions. */
@Component
public class RedisGateway {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end
        local u = tonumber(redis.call('GET', KEYS[1]) or '0')
        local s = tonumber(redis.call('GET', KEYS[2]) or '0')
        if u >= tonumber(ARGV[1]) or s >= tonumber(ARGV[2]) then
          local wait = 0
          if u >= tonumber(ARGV[1]) then wait = math.max(wait, redis.call('PTTL', KEYS[1])) end
          if s >= tonumber(ARGV[2]) then wait = math.max(wait, redis.call('PTTL', KEYS[2])) end
          return math.max(1, wait)
        end
        if redis.call('INCR', KEYS[1]) == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
        if redis.call('INCR', KEYS[2]) == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
        redis.call('SET', KEYS[3], '1', 'PX', ARGV[3])
        return 0
        """, Long.class);

    public RedisGateway(StringRedisTemplate redis) { this.redis = redis; }
    public String get(String key) { return redis.opsForValue().get(key); }
    public void put(String key, String json, int ttlSeconds) {
        redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
    }
    public long limit(List<String> keys, int userLimit, int sessionLimit, int windowMillis) {
        Long result = redis.execute(LIMIT, keys, Integer.toString(userLimit),
                Integer.toString(sessionLimit), Integer.toString(windowMillis));
        if (result == null) throw new IllegalStateException("Missing Redis limiter result");
        return result;
    }
}
