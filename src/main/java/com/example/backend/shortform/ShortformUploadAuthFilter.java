package com.example.backend.shortform;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.exception.AuthException;
import com.example.backend.common.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Authenticate before the servlet multipart resolver writes uploaded files to disk. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ShortformUploadAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper mapper;
    public ShortformUploadAuthFilter(AuthService auth, ObjectMapper mapper) { this.auth = auth; this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !path.matches("(/api/shortforms/[^/]+/assets|/api/community/posts)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        try { auth.getCurrentUser(request.getHeader("Authorization")); }
        catch (AuthException error) {
            response.setStatus(error.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write(mapper.writeValueAsBytes(new ApiError(error.getStatus().name(), error.getMessage())));
            return;
        }
        chain.doFilter(request, response);
    }
}
