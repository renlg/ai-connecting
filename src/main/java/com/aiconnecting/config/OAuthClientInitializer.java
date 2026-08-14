package com.aiconnecting.config;

import com.aiconnecting.entity.OAuthClient;
import com.aiconnecting.repository.OAuthClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuthClientInitializer implements ApplicationRunner {

    private final OAuthClientRepository clientRepository;

    @Value("${app.oauth.taiwei.secret:taiwei-secret-2026}")
    private String taiweiSecret;

    @Value("${app.oauth.taiwei.redirect-uri:http://127.0.0.1:8688/api/oauth/callback}")
    private String taiweiRedirectUri;

    @Override
    public void run(ApplicationArguments args) {
        if (clientRepository.existsByClientId("taiwei")) return;

        OAuthClient client = OAuthClient.builder()
                .clientId("taiwei")
                // Plaintext is intentional for this internal registry; never log this value.
                .clientSecret(taiweiSecret)
                .redirectUri(taiweiRedirectUri)
                .name("Taiwei Gateway")
                .enabled(true)
                .build();
        try {
            clientRepository.saveAndFlush(client);
            log.info("Initialized OAuth client 'taiwei' with redirect URI {}", taiweiRedirectUri);
        } catch (DataIntegrityViolationException e) {
            if (!clientRepository.existsByClientId("taiwei")) throw e;
            log.debug("OAuth client 'taiwei' was initialized by another application instance");
        }
    }
}
