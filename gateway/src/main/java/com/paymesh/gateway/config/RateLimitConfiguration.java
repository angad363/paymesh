package com.paymesh.gateway.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Backs the routes' rate limiter with Redis, so the limit is shared across gateway instances rather
 * than counted per-process (which would let N gateways admit N times the intended traffic).
 *
 * <p>The webmvc gateway's {@code rateLimit()} filter looks up an {@link AsyncProxyManager} bean and
 * builds a distributed bucket against it. We give it one backed by Redis through bucket4j's Lettuce
 * module; nothing else in the gateway touches Redis, which is why there is no Spring Data Redis here
 * -- just the raw client bucket4j needs.
 */
@Configuration
public class RateLimitConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(@Value("${paymesh.gateway.redis-uri}") String redisUri) {
        return RedisClient.create(redisUri);
    }

    @Bean
    AsyncProxyManager<String> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        // STRING KEYS, byte[] VALUES. The gateway's rateLimit() filter passes the bucket key it
        // resolves -- here the caller's IP, a String -- straight to the proxy manager. builderFor(
        // RedisClient) would build a byte[]-keyed manager and the String key would fail to encode
        // (String cannot be cast to [B). Connecting with a String/byte[] codec makes the manager
        // String-keyed so the filter's key type matches, while bucket4j's serialized bucket state
        // stays raw bytes.
        StatefulRedisConnection<String, byte[]> connection = rateLimitRedisClient.connect(
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection)
            // Let a bucket's Redis key expire once it has had time to refill fully, so keys for
            // one-off clients do not accumulate forever. Two minutes comfortably covers the
            // minute-scale refill windows the routes configure.
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
            .build()
            .asAsync();
    }
}
