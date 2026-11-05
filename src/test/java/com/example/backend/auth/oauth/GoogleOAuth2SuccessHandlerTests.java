package com.example.backend.auth.oauth;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.AuthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleOAuth2SuccessHandlerTests {

    @Test
    void verifiedGoogleUserReceivesJwtAtFrontendCallback() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.loginWithGoogle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AuthResponse("header.payload.signature", "Bearer", 3600, null));
        GoogleOAuth2SuccessHandler handler = handler(authService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication(Map.of(
                        "sub", "google-subject",
                        "email", "user@example.com",
                        "email_verified", true,
                        "name", "사용자",
                        "picture", "https://example.com/profile.png"
                ))
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:3000/oauth/callback?accessToken=header.payload.signature&tokenType=Bearer&expiresIn=3600");
    }

    @Test
    void unverifiedEmailRedirectsToLoginWithError() throws Exception {
        AuthService authService = mock(AuthService.class);
        GoogleOAuth2SuccessHandler handler = handler(authService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                authentication(Map.of(
                        "sub", "google-subject",
                        "email", "user@example.com",
                        "email_verified", false
                ))
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:3000/login?error=unverified_email");
        verify(authService, never()).loginWithGoogle(anyString(), anyString(), anyString(), anyString());
    }

    private static GoogleOAuth2SuccessHandler handler(AuthService authService) {
        return new GoogleOAuth2SuccessHandler(
                authService,
                "http://localhost:3000/oauth/callback",
                "http://localhost:3000/login"
        );
    }

    private static OAuth2AuthenticationToken authentication(Map<String, Object> attributes) {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "sub"
        );
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }
}
