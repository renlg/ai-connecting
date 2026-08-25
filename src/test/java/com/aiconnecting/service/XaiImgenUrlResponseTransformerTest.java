package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class XaiImgenUrlResponseTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XaiImgenUrlResponseTransformer transformer =
            new XaiImgenUrlResponseTransformer(objectMapper, "http://207.57.184.239");

    @Test
    void supports_onlyMatchesGrokImagineImageV20() {
        assertThat(transformer.supports("grok-imagine-image-2.0")).isTrue();
        assertThat(transformer.supports("grok-2-image")).isFalse();
        assertThat(transformer.supports("dall-e-3")).isFalse();
        assertThat(transformer.supports(null)).isFalse();
    }

    @Test
    void transform_replacesImgenHostWithProxyAndKeepsPath() throws Exception {
        JsonNode result = transform("""
                {
                  "created": 123,
                  "data": [
                    {"url": "https://imgen.x.ai/xai-imgen/xxx.jpeg", "index": 0}
                  ],
                  "usage": {"total_tokens": 10}
                }
                """);

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("http://207.57.184.239/xai-imgen/xxx.jpeg");
        assertThat(result.path("data").get(0).path("index").asInt()).isEqualTo(0);
        assertThat(result.path("created").asInt()).isEqualTo(123);
        assertThat(result.path("usage").path("total_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void transform_rewritesHttpUrlWithoutTrailingSlash() throws Exception {
        JsonNode result = transform("{\"data\":[{\"url\":\"http://imgen.x.ai\"}]}");

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("http://207.57.184.239");
    }

    @Test
    void transform_preservesTrailingSlash() throws Exception {
        JsonNode result = transform("{\"data\":[{\"url\":\"https://imgen.x.ai/\"}]}");

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("http://207.57.184.239/");
    }

    @Test
    void transform_usesConfiguredProxyBaseUrl() throws Exception {
        XaiImgenUrlResponseTransformer configured =
                new XaiImgenUrlResponseTransformer(objectMapper, "https://images-proxy.example:8443");
        JsonNode result = objectMapper.readTree(configured.transform(
                "grok-imagine-image-2.0",
                "{\"data\":[{\"url\":\"https://imgen.x.ai/image.png\"}]}"));

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("https://images-proxy.example:8443/image.png");
    }

    @Test
    void constructor_rejectsProxyBaseUrlContainingPath() {
        assertThatThrownBy(() ->
                new XaiImgenUrlResponseTransformer(objectMapper, "http://proxy.example/prefix/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain a path");
    }

    @Test
    void transform_matchesHostCaseInsensitivelyAndPreservesQuery() throws Exception {
        JsonNode result = transform("""
                {"data":[{"url":"https://IMGEN.X.AI/a/b.png?token=A%2FB&size=1024"}]}
                """);

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("http://207.57.184.239/a/b.png?token=A%2FB&size=1024");
    }

    @Test
    void transform_leavesNonImgenDomainUntouched() {
        String input = "{\"data\":[{\"url\":\"https://example.com/xai-imgen/xxx.jpeg\"}]}";

        assertThat(transformer.transform("grok-imagine-image-2.0", input)).isEqualTo(input);
    }

    @Test
    void transform_returnsOriginalWhenNoDataArray() {
        String input = "{\"created\":123}";

        assertThat(transformer.transform("grok-imagine-image-2.0", input)).isEqualTo(input);
    }

    @Test
    void transform_returnsOriginalForInvalidJson() {
        String input = "not json";

        assertThat(transformer.transform("grok-imagine-image-2.0", input)).isEqualTo(input);
    }

    @Test
    void transform_returnsOriginalWhenUrlIsNotString() {
        String input = "{\"data\":[{\"url\":123}]}";

        assertThat(transformer.transform("grok-imagine-image-2.0", input)).isEqualTo(input);
    }

    @Test
    void transform_returnsOriginalForNonUrlContainingUpstreamText() {
        String input = "{\"data\":[{\"url\":\"not a URL mentioning imgen.x.ai\"}]}";

        assertThat(transformer.transform("grok-imagine-image-2.0", input)).isEqualTo(input);
    }

    @Test
    void transform_preservesOtherFieldsInDataItem() throws Exception {
        JsonNode result = transform("""
                {
                  "data": [
                    {"url": "https://imgen.x.ai/a/b.png", "seed": 42, "prompt": "cat"}
                  ]
                }
                """);

        assertThat(result.path("data").get(0).path("url").asText())
                .isEqualTo("http://207.57.184.239/a/b.png");
        assertThat(result.path("data").get(0).path("seed").asInt()).isEqualTo(42);
        assertThat(result.path("data").get(0).path("prompt").asText()).isEqualTo("cat");
    }

    private JsonNode transform(String body) throws Exception {
        return objectMapper.readTree(transformer.transform("grok-imagine-image-2.0", body));
    }
}
