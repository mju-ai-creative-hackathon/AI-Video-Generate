package com.example.backend.auth.google;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class GoogleTokenConfig {
    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean("googleJwtDecoder")
    JwtDecoder googleJwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
    }
}
