package com.example.backend.auth.oauth;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.AuthResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {
    private final AuthService authService;
    private final String frontendSuccessUrl;
    private final String frontendFailureUrl;

    public GoogleOAuth2SuccessHandler(
            AuthService authService,
            @Value("${app.oauth2.frontend-success-url}") String frontendSuccessUrl,
            @Value("${app.oauth2.frontend-failure-url}") String frontendFailureUrl
    ) {
        this.authService = authService;
        this.frontendSuccessUrl = frontendSuccessUrl;
        this.frontendFailureUrl = frontendFailureUrl;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String subject = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        Boolean emailVerified = principal.getAttribute("email_verified");

        if (subject == null || email == null || !Boolean.TRUE.equals(emailVerified)) {
            response.sendRedirect(failureRedirect("unverified_email"));
            return;
        }

        AuthResponse auth = authService.loginWithGoogle(
                subject,
                email,
                principal.getAttribute("name"),
                principal.getAttribute("picture")
        );

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendSuccessUrl)
                .queryParam("accessToken", auth.accessToken())
                .queryParam("tokenType", auth.tokenType())
                .queryParam("expiresIn", auth.expiresIn())
                .build()
                .encode()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }

    public String failureRedirect(String error) {
        return UriComponentsBuilder.fromUriString(frontendFailureUrl)
                .queryParam("error", error)
                .build()
                .encode()
                .toUriString();
    }
}
