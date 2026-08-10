package com.aiconnecting.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期校验 Redis / 集群相关开关之间的一致性，避免"多实例部署但 Redis 未真正启用"
 * 这种只会在运行时悄悄退化为单机行为、不易被发现的配置错误。
 *
 * - {@code app.redis.enabled}（独立开关，不再受 {@code app.rate-limit.enabled} 牵连）控制
 *   {@link RedisConfig} 是否装配，从而决定 {@link StringRedisTemplate} bean 是否存在。
 * - {@code app.cluster.enabled} 声明"本次部署是多实例集群"。集群模式下若 Redis 不可用，
 *   分布式锁/跨实例缓存失效广播会静默退化为单机行为，导致定时任务重复执行、缓存跨实例不一致，
 *   因此直接 FAIL FAST 拒绝启动，而不是让问题在运行时悄悄发生。
 */
@Slf4j
@Component
public class ClusterConfigValidator {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider;
    private final boolean clusterEnabled;
    private final boolean redisEnabled;
    private final boolean rateLimitEnabled;

    public ClusterConfigValidator(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                   ObjectProvider<RedisConnectionFactory> redisConnectionFactoryProvider,
                                   @Value("${app.cluster.enabled:false}") boolean clusterEnabled,
                                   @Value("${app.redis.enabled:false}") boolean redisEnabled,
                                   @Value("${app.rate-limit.enabled:false}") boolean rateLimitEnabled) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.redisConnectionFactoryProvider = redisConnectionFactoryProvider;
        this.clusterEnabled = clusterEnabled;
        this.redisEnabled = redisEnabled;
        this.rateLimitEnabled = rateLimitEnabled;
    }

    @PostConstruct
    public void validate() {
        boolean redisAvailable = redisTemplateProvider.getIfAvailable() != null;

        if (clusterEnabled && !redisAvailable) {
            throw new IllegalStateException(
                    "app.cluster.enabled=true（多实例部署模式）但 Redis 未启用/不可用：分布式锁与跨实例缓存失效广播"
                            + "会静默退化为单机行为，导致定时任务在每个实例上重复执行、缓存跨实例不一致。"
                            + "请设置 REDIS_ENABLED=true 并正确配置 REDIS_HOST/REDIS_PORT 等连接参数后重新启动，"
                            + "或者如果这确实是单实例部署，请不要设置 CLUSTER_ENABLED=true。");
        }

        if (clusterEnabled) {
            // Bean 存在不代表 Redis 可达：Lettuce 连接是懒连接，必须真正发起一次连接/PING 才能
            // 在启动期发现"配置了错误的 host/port/password"这类问题，而不是让分布式锁/缓存失效
            // 广播在运行时悄悄永久 fail-closed。
            verifyRedisConnectivity();
        }

        if (rateLimitEnabled && !redisEnabled) {
            log.warn("app.rate-limit.enabled=true 但 app.redis.enabled=false：限流与登录失败锁定功能不会生效"
                    + "（缺少 Redis 支撑，相关 Bean 不会装配）。如需限流，请同时设置 REDIS_ENABLED=true。");
        }

        if (!redisAvailable) {
            log.warn("当前未启用 Redis（app.redis.enabled=false）：分布式锁、跨实例缓存失效广播均降级为单机行为，"
                    + "仅适用于单实例部署。若计划部署多个实例，请设置 REDIS_ENABLED=true 与 CLUSTER_ENABLED=true，"
                    + "否则多实例间的定时任务/缓存一致性无法保证。");
        }
    }

    private void verifyRedisConnectivity() {
        RedisConnectionFactory connectionFactory = redisConnectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            // redisAvailable 已经是 false 的情况在上面已经 throw，这里是防御性判断，理论上不会触发。
            throw new IllegalStateException(
                    "app.cluster.enabled=true 但未找到 RedisConnectionFactory，无法校验 Redis 连通性。");
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "app.cluster.enabled=true（多实例部署模式）但连接 Redis 失败（PING 未成功）："
                            + e.getMessage() + "。分布式锁与跨实例缓存失效广播依赖 Redis 可用，"
                            + "请检查 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 等连接参数后重新启动。", e);
        }
    }
}
