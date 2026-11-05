package com.example.backend.auth;

import com.example.backend.auth.dto.AuthResponse;
import com.example.backend.auth.google.GoogleIdTokenVerifier;
import com.example.backend.auth.google.GoogleUserInfo;
import org.springframework.stereotype.Service;

@Service
public class GoogleLoginService {
    private final GoogleIdTokenVerifier tokenVerifier;
    private final AuthService authService;

    public GoogleLoginService(GoogleIdTokenVerifier tokenVerifier, AuthService authService) {
        this.tokenVerifier = tokenVerifier;
        this.authService = authService;
    }

    public AuthResponse login(String idToken) {
        GoogleUserInfo googleUser = tokenVerifier.verify(idToken);
        return authService.loginWithGoogle(
                googleUser.subject(),
                googleUser.email(),
                googleUser.name(),
                googleUser.profileImageUrl()
        );
    }
}
