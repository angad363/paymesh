package com.paymesh.shared.idempotency.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which routes require an {@code Idempotency-Key}, declared as {@code "METHOD /path/template"}.
 * <p>
 * Nothing is idempotent by default: a route that is not listed here passes through the filter
 * untouched. Registration is a line in
 * {@code com.paymesh.shared.idempotency.infrastructure.config.IdempotencyConfiguration}, which is
 * the one place to look and the one place to add to.
 * <p>
 * The declaration is the <em>template</em>, and the template is what gets stored as the record's
 * endpoint. Storing the concrete URI instead would scope a key per-resource --
 * {@code /api/v1/orders/ord_abc/cancel} and {@code /api/v1/orders/ord_xyz/cancel} would be two
 * different endpoints -- which is not the scope the SDD specifies.
 */
public final class IdempotentRoutes {

    private final List<Route> routes;

    public IdempotentRoutes(Collection<String> declarations) {
        PathPatternParser parser = new PathPatternParser();

        this.routes = declarations.stream()
            .map(declaration -> Route.parse(declaration, parser))
            .toList();
    }

    /**
     * The path template this request must be scoped by, or empty when the route is not idempotent.
     */
    public Optional<String> templateFor(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(pathWithinApplication(request));
        String method = request.getMethod().toUpperCase(Locale.ROOT);

        return routes.stream()
            .filter(route -> route.method.equals(method) && route.pattern.matches(path))
            .findFirst()
            .map(route -> route.declaration);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();

        return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    private record Route(String method, PathPattern pattern, String declaration) {

        static Route parse(String declaration, PathPatternParser parser) {
            int space = declaration.indexOf(' ');

            if (space < 1) {
                throw new IllegalArgumentException(
                    "An idempotent route must be declared as \"METHOD /path/template\", got: "
                        + declaration
                );
            }

            return new Route(
                declaration.substring(0, space).toUpperCase(Locale.ROOT),
                parser.parse(declaration.substring(space + 1).trim()),
                declaration
            );
        }
    }
}
