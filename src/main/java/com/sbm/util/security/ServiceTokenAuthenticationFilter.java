package com.sbm.util.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-SBM-Service-Token";

    private final String serviceToken;

    public ServiceTokenAuthenticationFilter(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = request.getHeader(HEADER);

        if (serviceToken == null || serviceToken.isBlank() || !serviceToken.equals(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }
}