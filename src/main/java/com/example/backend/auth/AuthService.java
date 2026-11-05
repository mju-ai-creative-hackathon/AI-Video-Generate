package com.example.backend.auth;

import com.example.backend.auth.domain.User;
import com.example.backend.auth.domain.UserRepository;
import com.example.backend.auth.dto.AuthResponse;
import com.example.backend.auth.dto.LoginRequest;
import com.example.backend.auth.dto.RefreshTokenRequest;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.auth.dto.UserResponse;
import com.example.backend.auth.exception.AuthException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthService {
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new AuthException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        try {
            User user = userRepository.saveAndFlush(new User(
                    email,
                    request.name().trim(),
                    passwordEncoder.encode(request.password())
            ));
            return createAuthResponse(user);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(AuthService::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return createAuthResponse(user);
    }

    @Transactional
    public AuthResponse loginWithGoogle(String subject, String email, String name, String profileImageUrl) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByGoogleSubject(subject)
                .orElseGet(() -> userRepository.findByEmail(normalizedEmail)
                        .orElseGet(() -> new User(
                                normalizedEmail,
                                normalizedGoogleName(name, normalizedEmail),
                                passwordEncoder.encode(UUID.randomUUID().toString())
                        )));

        user.linkGoogleAccount(subject, normalizedGoogleName(name, normalizedEmail), profileImageUrl);
        return createAuthResponse(userRepository.save(user));
    }

    public UserResponse getCurrentUser(String authorizationHeader) {
        UUID userId = jwtTokenProvider.parseUserId(extractBearerToken(authorizationHeader));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        return UserResponse.from(user);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(request.refreshToken());
        return new AuthResponse(
                jwtTokenProvider.createToken(rotated.user().getId()),
                "Bearer",
                jwtTokenProvider.getExpirationSeconds(),
                rotated.token(),
                rotated.expiresIn(),
                UserResponse.from(rotated.user())
        );
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revokeFamily(request.refreshToken());
    }

    private AuthResponse createAuthResponse(User user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(
                jwtTokenProvider.createToken(user.getId()),
                "Bearer",
                jwtTokenProvider.getExpirationSeconds(),
                refreshToken.token(),
                refreshToken.expiresIn(),
                UserResponse.from(user)
        );
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedGoogleName(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return email.substring(0, email.indexOf('@'));
    }

    private static String extractBearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Bearer 인증 토큰이 필요합니다.");
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Bearer 인증 토큰이 필요합니다.");
        }
        return token;
    }

    private static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
