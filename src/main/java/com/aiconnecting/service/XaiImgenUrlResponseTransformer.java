package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** 将 grok-imagine-image-2.0 响应 data[].url 中的 imgen.x.ai 域名替换为可配置的反代地址。 */
@Component
@Slf4j
public class XaiImgenUrlResponseTransformer implements ResponseTransformer {

    private static final String MODEL = "grok-imagine-image-2.0";
    private static final String UPSTREAM_DOMAIN = "imgen.x.ai";

    private final ObjectMapper objectMapper;
    private final URI proxyBaseUri;

    public XaiImgenUrlResponseTransformer(
            ObjectMapper objectMapper,
            @Value("${app.xai-image-proxy-base-url:http://207.57.184.239}") String proxyBaseUrl) {
        this.objectMapper = objectMapper;
        this.proxyBaseUri = parseProxyBaseUri(proxyBaseUrl);
    }

    @Override
    public boolean supports(String model) {
        return MODEL.equals(model);
    }

    @Override
    public String transform(String model, String responseBodyJson) {
        try {
            JsonNode body = objectMapper.readTree(responseBodyJson);
            if (!(body instanceof ObjectNode objectBody)) {
                return responseBodyJson;
            }
            JsonNode data = objectBody.get("data");
            if (data == null || !data.isArray()) {
                return responseBodyJson;
            }
            boolean changed = false;
            for (JsonNode item : data) {
                if (!(item instanceof ObjectNode itemObject)) {
                    continue;
                }
                JsonNode url = itemObject.get("url");
                if (url == null || !url.isTextual()) {
                    continue;
                }
                String urlValue = url.asText();
                if (!urlValue.toLowerCase(Locale.ROOT).contains(UPSTREAM_DOMAIN)) {
                    continue;
                }
                String rewritten = rewriteUrl(urlValue);
                if (rewritten != null && !rewritten.equals(urlValue)) {
                    itemObject.put("url", rewritten);
                    changed = true;
                } else {
                    log.warn("xAI 图片 URL 包含 {} 但未能改写: url={}", UPSTREAM_DOMAIN, urlValue);
                }
            }
            return changed ? objectMapper.writeValueAsString(objectBody) : responseBodyJson;
        } catch (Exception e) {
            return responseBodyJson;
        }
    }

    private String rewriteUrl(String value) {
        try {
            URI upstream = URI.create(value);
            if (upstream.getHost() == null || !UPSTREAM_DOMAIN.equalsIgnoreCase(upstream.getHost())) {
                return null;
            }
            StringBuilder rewritten = new StringBuilder()
                    .append(proxyBaseUri.getScheme())
                    .append("://")
                    .append(proxyBaseUri.getRawAuthority());
            if (upstream.getRawPath() != null) {
                rewritten.append(upstream.getRawPath());
            }
            if (upstream.getRawQuery() != null) {
                rewritten.append('?').append(upstream.getRawQuery());
            }
            if (upstream.getRawFragment() != null) {
                rewritten.append('#').append(upstream.getRawFragment());
            }
            return rewritten.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private URI parseProxyBaseUri(String value) {
        URI uri = URI.create(value);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("app.xai-image-proxy-base-url must be an absolute URL");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException(
                    "app.xai-image-proxy-base-url must not contain a path; use scheme://host[:port]");
        }
        return uri;
    }
}
