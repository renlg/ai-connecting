package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 将 grok-imagine-image-2.0 响应 data[].url 中的 imgen.x.ai 域名替换为反代地址（https→http，域名→IP，路径不变）。 */
@Component
public class XaiImgenUrlResponseTransformer implements ResponseTransformer {

    private static final String MODEL = "grok-imagine-image-2.0";
    private static final String UPSTREAM_URL_PREFIX = "https://imgen.x.ai/";
    private static final String PROXY_URL_PREFIX = "http://207.57.184.239/";
    private static final String UPSTREAM_DOMAIN = "imgen.x.ai";

    private final ObjectMapper objectMapper;

    public XaiImgenUrlResponseTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                if (!urlValue.contains(UPSTREAM_DOMAIN)) {
                    continue;
                }
                String rewritten = urlValue.replace(UPSTREAM_URL_PREFIX, PROXY_URL_PREFIX);
                if (!rewritten.equals(urlValue)) {
                    itemObject.put("url", rewritten);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(objectBody) : responseBodyJson;
        } catch (Exception e) {
            return responseBodyJson;
        }
    }
}
