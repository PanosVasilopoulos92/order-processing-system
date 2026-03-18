package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;
import org.viators.orderprocessingsystem.exceptions.dto.ErrorResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Handles 401 Unauthorized — authentication required but not provided/valid.
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        log.debug("Authentication failed for {} {} - {}",
            request.getMethod(), request.getRequestURI(), authException.getMessage());

        writeErrorResponse(
            response,
            HttpStatus.UNAUTHORIZED,
            ErrorCodeEnum.INVALID_CREDENTIALS,
            "Authentication is required to access this resource",
            request.getRequestURI()
        );
    }

    /**
     * Handles 403 Forbidden — authenticated but insufficient permissions.
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        log.warn("Access denied for {} {} - User lacks required permissions",
            request.getMethod(), request.getRequestURI());

        writeErrorResponse(
            response,
            HttpStatus.FORBIDDEN,
            ErrorCodeEnum.ACCESS_DENIED,
            "You do not have permission to access this resource",
            request.getRequestURI()
        );
    }

    /**
     * Writes a consistent JSON error response directly to the servlet response.
     *
     * <p><strong>Why write directly to the response?</strong> These exceptions
     * occur in the security filter chain, which runs before the DispatcherServlet.
     * Our {@code @RestControllerAdvice} (GlobalExceptionHandler) only catches
     * exceptions from controllers. At the filter level, we must write the
     * response ourselves using the raw HttpServletResponse.
     */
    private void writeErrorResponse(HttpServletResponse response,
                                    HttpStatus status,
                                    ErrorCodeEnum errorCode,
                                    String message,
                                    String path) throws IOException {

        ErrorResponse errorResponse = ErrorResponse.of(
            status.value(), errorCode, message, path
        );

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
