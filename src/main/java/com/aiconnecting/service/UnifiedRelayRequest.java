package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Protocol-neutral request metadata.  The original JSON is deliberately retained: converters and
 * custom channels must not lose vendor-specific fields merely because the core does not understand them.
 */
public record UnifiedRelayRequest(
        RelayProtocol protocol,
        String path,
        String model,
        boolean stream,
        Integer maxTokens,
        Double temperature,
        JsonNode content,
        String rawBody
) {
}
