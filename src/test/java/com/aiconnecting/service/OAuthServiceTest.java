package com.aiconnecting.service;

import com.aiconnecting.entity.OAuthClient;
import com.aiconnecting.entity.OAuthCode;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.OAuthClientRepository;
import com.aiconnecting.repository.OAuthCodeRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    @Mock private OAuthClientRepository clientRepository;
    @Mock private OAuthCodeRepository codeRepository;
    @Mock private UserRepository userRepository;
    @Mock private JwtUtils jwtUtils;

    private OAuthService service;
    private OAuthClient client;

    @BeforeEach
    void setUp() {
        service = new OAuthService(clientRepository, codeRepository, userRepository, jwtUtils);
        client = OAuthClient.builder()
                .clientId("taiwei")
                .clientSecret("secret")
                .redirectUri("http://127.0.0.1:8688/api/oauth/callback")
                .name("Taiwei Gateway")
                .enabled(true)
                .build();
    }

    @Test
    void createAuthorizationCodePersistsFiveMinuteSingleUseCode() {
        when(codeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime before = LocalDateTime.now();

        OAuthCode result = service.createAuthorizationCode("alice", client);

        ArgumentCaptor<OAuthCode> captor = ArgumentCaptor.forClass(OAuthCode.class);
        verify(codeRepository).save(captor.capture());
        OAuthCode saved = captor.getValue();
        assertSame(saved, result);
        assertTrue(saved.getCode().matches("[0-9a-f]{32}"));
        assertEquals("alice", saved.getUsername());
        assertEquals("taiwei", saved.getClientId());
        assertFalse(saved.getUsed());
        assertTrue(saved.getExpiresAt().isAfter(before.plusMinutes(4).plusSeconds(59)));
        assertTrue(saved.getExpiresAt().isBefore(LocalDateTime.now().plusMinutes(5).plusSeconds(1)));
    }

    @Test
    void authorizationRequestValidationRejectsBadClientRedirectAndResponseType() {
        when(clientRepository.findByClientId("missing")).thenReturn(Optional.empty());
        assertEquals(400, assertThrows(OAuthException.class,
                () -> service.validateAuthorizationRequest("missing", client.getRedirectUri(), "code"))
                .getStatus().value());

        when(clientRepository.findByClientId("taiwei")).thenReturn(Optional.of(client));
        assertEquals(400, assertThrows(OAuthException.class,
                () -> service.validateAuthorizationRequest("taiwei", "http://wrong/callback", "code"))
                .getStatus().value());
        assertEquals(400, assertThrows(OAuthException.class,
                () -> service.validateAuthorizationRequest("taiwei", client.getRedirectUri(), "token"))
                .getStatus().value());
    }

    @Test
    void authorizeLoginAcceptsBearerHeaderOrAicCookie() {
        when(jwtUtils.validateToken("header-token")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("header-token")).thenReturn("alice");
        when(jwtUtils.validateToken("cookie-token")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("cookie-token")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser()));

        MockHttpServletRequest headerRequest = new MockHttpServletRequest();
        headerRequest.addHeader("Authorization", "Bearer header-token");
        assertEquals("alice", service.authenticatedUsername(headerRequest));

        MockHttpServletRequest cookieRequest = new MockHttpServletRequest();
        cookieRequest.setCookies(new Cookie("aic_token", "cookie-token"));
        assertEquals("alice", service.authenticatedUsername(cookieRequest));
    }

    @Test
    void exchangeCodeReturnsJwtAndUsername() {
        OAuthCode code = validCode();
        when(clientRepository.findByClientId("taiwei")).thenReturn(Optional.of(client));
        when(codeRepository.findById("code-1")).thenReturn(Optional.of(code));
        when(codeRepository.markUsed("code-1")).thenReturn(1);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(jwtUtils.generateToken("alice", "user")).thenReturn("jwt-token");

        Map<String, Object> response = service.exchangeCode(
                "taiwei", "secret", "code-1", "authorization_code");

        assertEquals("jwt-token", response.get("access_token"));
        assertEquals("bearer", response.get("token_type"));
        assertEquals(86400L, response.get("expires_in"));
        assertEquals("alice", response.get("username"));
        verify(codeRepository).markUsed("code-1");
    }

    @Test
    void reusedCodeIsRejected() {
        OAuthCode code = validCode();
        code.setUsed(true);
        when(clientRepository.findByClientId("taiwei")).thenReturn(Optional.of(client));
        when(codeRepository.findById("code-1")).thenReturn(Optional.of(code));

        OAuthException exception = assertThrows(OAuthException.class, () -> service.exchangeCode(
                "taiwei", "secret", "code-1", "authorization_code"));

        assertEquals(400, exception.getStatus().value());
        verify(codeRepository, never()).markUsed(any());
    }

    @Test
    void expiredCodeIsRejected() {
        OAuthCode code = validCode();
        code.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(clientRepository.findByClientId("taiwei")).thenReturn(Optional.of(client));
        when(codeRepository.findById("code-1")).thenReturn(Optional.of(code));

        OAuthException exception = assertThrows(OAuthException.class, () -> service.exchangeCode(
                "taiwei", "secret", "code-1", "authorization_code"));

        assertEquals(400, exception.getStatus().value());
        verify(codeRepository, never()).markUsed(any());
    }

    @Test
    void userInfoValidatesJwt() {
        when(jwtUtils.validateToken("valid")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("valid")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser()));

        assertEquals(Map.of("username", "alice"), service.userInfo("Bearer valid"));

        when(jwtUtils.validateToken("invalid")).thenReturn(false);
        OAuthException exception = assertThrows(OAuthException.class,
                () -> service.userInfo("Bearer invalid"));
        assertEquals(401, exception.getStatus().value());
    }

    private OAuthCode validCode() {
        return OAuthCode.builder()
                .code("code-1")
                .username("alice")
                .clientId("taiwei")
                .redirectUri(client.getRedirectUri())
                .expiresAt(LocalDateTime.now().plusMinutes(1))
                .used(false)
                .build();
    }

    private User activeUser() {
        return User.builder().username("alice").role("user").status(1).build();
    }
}
