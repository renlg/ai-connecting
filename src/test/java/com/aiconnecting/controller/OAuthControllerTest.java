package com.aiconnecting.controller;

import com.aiconnecting.entity.OAuthClient;
import com.aiconnecting.entity.OAuthCode;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.service.OAuthException;
import com.aiconnecting.service.OAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class OAuthControllerTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:8688/api/oauth/callback";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OAuthService oauthService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private OAuthClient client;

    @BeforeEach
    void setUp() {
        client = OAuthClient.builder()
                .clientId("taiwei")
                .clientSecret("secret")
                .redirectUri(REDIRECT_URI)
                .name("Taiwei Gateway")
                .enabled(true)
                .build();
        when(oauthService.validateAuthorizationRequest("taiwei", REDIRECT_URI, "code"))
                .thenReturn(client);
    }

    @Test
    void authorizeWithoutLoginRedirectsToSpaLogin() throws Exception {
        mockMvc.perform(get("/api/oauth/authorize")
                        .param("client_id", "taiwei")
                        .param("redirect_uri", REDIRECT_URI)
                        .param("response_type", "code")
                        .param("state", "abc"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("http://localhost/login?next=*"))
                .andExpect(header().string("Location", containsString("%2Fapi%2Foauth%2Fauthorize")))
                .andExpect(header().string("Set-Cookie", containsString("oauth_pending=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=600")));
    }

    @Test
    void authorizeWithLoginPersistsCodeAndRedirects() throws Exception {
        when(oauthService.authenticatedUsername(any())).thenReturn("alice");
        when(oauthService.createAuthorizationCode("alice", client)).thenReturn(OAuthCode.builder()
                .code("0123456789abcdef0123456789abcdef")
                .build());

        mockMvc.perform(get("/api/oauth/authorize")
                        .param("client_id", "taiwei")
                        .param("redirect_uri", REDIRECT_URI)
                        .param("response_type", "code")
                        .param("state", "abc"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(REDIRECT_URI
                        + "?code=0123456789abcdef0123456789abcdef&state=abc"));

        verify(oauthService).createAuthorizationCode("alice", client);
    }

    @Test
    void badAuthorizationParametersReturn400() throws Exception {
        when(oauthService.validateAuthorizationRequest(anyString(), anyString(), anyString()))
                .thenThrow(new OAuthException(HttpStatus.BAD_REQUEST, "invalid request"));

        for (String[] params : new String[][]{
                {"bad-client", REDIRECT_URI, "code"},
                {"taiwei", "http://wrong/callback", "code"},
                {"taiwei", REDIRECT_URI, "token"}
        }) {
            mockMvc.perform(get("/api/oauth/authorize")
                            .param("client_id", params[0])
                            .param("redirect_uri", params[1])
                            .param("response_type", params[2]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void tokenAcceptsFormAndReturnsAccessToken() throws Exception {
        when(oauthService.exchangeCode("taiwei", "secret", "code-1", "authorization_code"))
                .thenReturn(Map.of("access_token", "jwt", "token_type", "bearer",
                        "expires_in", 86400L, "username", "alice"));

        mockMvc.perform(post("/api/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_id", "taiwei")
                        .param("client_secret", "secret")
                        .param("code", "code-1")
                        .param("grant_type", "authorization_code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("jwt"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void userInfoValidAndInvalidToken() throws Exception {
        when(oauthService.userInfo("Bearer valid")).thenReturn(Map.of("username", "alice"));
        when(oauthService.userInfo("Bearer invalid"))
                .thenThrow(new OAuthException(HttpStatus.UNAUTHORIZED, "Invalid access token"));

        mockMvc.perform(get("/api/oauth/userinfo").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
        mockMvc.perform(get("/api/oauth/userinfo").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
}
