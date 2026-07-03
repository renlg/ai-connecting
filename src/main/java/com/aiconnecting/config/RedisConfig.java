package com.aiconnecting.config;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类
 */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.username:}")
    private String redisUsername;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * 自定义 Lettuce ClientResources，使用 JVM 默认 DNS 解析器
     * 解决 Lettuce/Netty 内部 DNS 解析器无法解析某些域名的问题
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
                .dnsResolver(DnsResolver.jvmDefault())
                .build();
    }

    /**
     * 自定义 LettuceConnectionFactory，使用 JVM DNS 解析
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        redisConfig.setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }
        if (redisUsername != null && !redisUsername.isEmpty()) {
            redisConfig.setUsername(redisUsername);
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .build();

        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Long> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Long> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericToStringSerializer<>(Long.class));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericToStringSerializer<>(Long.class));
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 限流 Lua 脚本
     * 基于滑动窗口算法实现精确的速率限制
     *
     * KEYS[1] = 限流 key (如 rate_limit:channel:{channelId})
     * ARGV[1] = 窗口大小（毫秒）
     * ARGV[2] = 最大请求数
     * ARGV[3] = 当前时间戳（毫秒）
     *
     * 返回: 1=允许, 0=拒绝
     */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        String luaScript = """
                local key = KEYS[1]
                local window = tonumber(ARGV[1])
                local maxRequests = tonumber(ARGV[2])
                local now = tonumber(ARGV[3])
                
                -- 移除窗口外的请求
                redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
                
                -- 当前窗口内的请求数
                local currentCount = redis.call('ZCARD', key)
                
                if currentCount < maxRequests then
                    -- 未超限，添加当前请求
                    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
                    redis.call('PEXPIRE', key, window)
                    return 1
                else
                    return 0
                end
                """;
        return new DefaultRedisScript<>(luaScript, Long.class);
    }
}
