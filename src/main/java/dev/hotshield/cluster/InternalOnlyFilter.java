package dev.hotshield.cluster;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

// /internal is for node-to-node calls only. The ALB and nginx block the path, but the ALB matches the raw path,
// so "//internal/..." can slip past it while Tomcat still routes it here. Both proxies add X-Forwarded-For and a
// client can't strip it, so any /internal request carrying it came from outside. The path is checked after
// decoding and collapsing slashes, the same way the request is routed, so encoded variants don't get past.
@Component
public class InternalOnlyFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("X-Forwarded-For") != null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
