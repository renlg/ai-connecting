package com.aiconnecting.config;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 全链路追踪配置
 * 为 OkHttp 客户端注入 B3 追踪头，使下游服务能加入同一条 trace
 */
@Configuration
@Slf4j
public class TracingConfig {

    /**
     * 响应头追踪过滤器 - 将 traceId 写入 HTTP 响应头，方便客户端排查问题
     */
    @Bean
    public FilterRegistrationBean<Filter> tracingResponseFilter(Tracing tracing) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TracingResponseFilter(tracing));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    private static class TracingResponseFilter implements Filter {
        private final Tracing tracing;

        TracingResponseFilter(Tracing tracing) {
            this.tracing = tracing;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            Span currentSpan = tracing.tracer().currentSpan();
            if (currentSpan != null) {
                httpResponse.setHeader("X-Trace-Id", currentSpan.context().traceIdString());
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * OkHttp 追踪拦截器 - 将当前 trace 上下文通过 B3 头传递给下游服务
     */
    @Bean
    public okhttp3.Interceptor tracingInterceptor(Tracing tracing) {
        return new TracingInterceptor(tracing);
    }

    private static class TracingInterceptor implements okhttp3.Interceptor {
        private final Tracing tracing;

        TracingInterceptor(Tracing tracing) {
            this.tracing = tracing;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            Request.Builder requestBuilder = original.newBuilder();

            // 注入 B3 追踪头
            Span currentSpan = tracing.tracer().currentSpan();
            if (currentSpan != null) {
                String traceId = currentSpan.context().traceIdString();
                String spanId = currentSpan.context().spanIdString();
                String parentSpanId = currentSpan.context().parentIdString();

                requestBuilder.addHeader("X-B3-TraceId", traceId);
                requestBuilder.addHeader("X-B3-SpanId", spanId);
                if (parentSpanId != null) {
                    requestBuilder.addHeader("X-B3-ParentSpanId", parentSpanId);
                }
                requestBuilder.addHeader("X-B3-Sampled", "1");
            }

            return chain.proceed(requestBuilder.build());
        }
    }
}
