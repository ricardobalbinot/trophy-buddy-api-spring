package dev.trophybuddy.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain security(HttpSecurity http, FirebaseIdentity identity,
			@Value("${trophybuddy.cors.origins}") String origins) throws Exception {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
		cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
		cors.setExposedHeaders(List.of("X-Request-ID"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);

		return http.cors(config -> config.configurationSource(source))
			.csrf(config -> config.disable())
			.requestCache(config -> config.disable())
			.sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(config -> config
				.requestMatchers("/healthz", "/readyz", "/actuator/health", "/api/v1/catalog", "/docs", "/openapi.json",
						"/openapi.json/**", "/swagger-ui/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.exceptionHandling(
					config -> config.authenticationEntryPoint((request, response, failure) -> unauthorized(response)))
			.addFilterBefore(new FirebaseBearerFilter(identity), AnonymousAuthenticationFilter.class)
			.build();
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		writeError(response, 401, "authentication_required");
	}

	private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.getWriter().write("{\"error\":\"" + code + "\"}");
	}

	private static final class FirebaseBearerFilter extends OncePerRequestFilter {

		private final FirebaseIdentity identity;

		private FirebaseBearerFilter(FirebaseIdentity identity) {
			this.identity = identity;
		}

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			String path = request.getRequestURI().substring(request.getContextPath().length());
			return !path.equals("/api/v1/library") && !path.startsWith("/api/v1/library/");
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String header = request.getHeader(HttpHeaders.AUTHORIZATION);
			if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
				String uid;
				try {
					uid = identity.verify(header.substring(7).trim());
				}
				catch (FirebaseIdentity.IdentityUnavailable failure) {
					writeError(response, 503, "service_unavailable");
					return;
				}

				if (uid == null) {
					unauthorized(response);
					return;
				}

				var context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(uid, null, List.of()));
				SecurityContextHolder.setContext(context);
			}
			chain.doFilter(request, response);
		}

	}

}
