package com.vaultri.core.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Renders RFC 7807 ProblemDetail responses for 401 Unauthorized and 403 Forbidden scenarios.
 */
@Component
@RequiredArgsConstructor
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required to access this resource or token is invalid/expired"
        );
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://vaultri.com/errors/unauthorized"));
        problem.setInstance(URI.create(request.getRequestURI()));

        writeProblemResponse(response, HttpStatus.UNAUTHORIZED, problem);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access to this resource is denied"
        );
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://vaultri.com/errors/forbidden"));
        problem.setInstance(URI.create(request.getRequestURI()));

        writeProblemResponse(response, HttpStatus.FORBIDDEN, problem);
    }

    private void writeProblemResponse(HttpServletResponse response, HttpStatus status, ProblemDetail problem)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
