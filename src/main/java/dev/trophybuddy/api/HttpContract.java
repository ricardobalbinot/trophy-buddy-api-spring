package dev.trophybuddy.api;

import dev.trophybuddy.api.support.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class HttpContract {

	@ExceptionHandler(ApiFailure.class)
	ResponseEntity<Map<String, String>> applicationFailure(ApiFailure failure) {
		var response = ResponseEntity.status(failure.status());
		if (failure.status() == 429) {
			response.header("Retry-After", "15");
		}
		return response.body(Map.of("error", failure.getMessage()));
	}

	@ExceptionHandler({ org.springframework.http.converter.HttpMessageNotReadableException.class,
			org.springframework.web.bind.MissingServletRequestParameterException.class })
	ResponseEntity<Map<String, String>> invalidRequest() {
		return ResponseEntity.unprocessableContent().body(Map.of("error", "invalid_request"));
	}

	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<Map<String, String>> unavailable() {
		return ResponseEntity.status(503).body(Map.of("error", "service_unavailable"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Map<String, Object>> invalidQuery(MethodArgumentTypeMismatchException failure) {
		return ResponseEntity.unprocessableContent()
			.body(Map.of("detail", List
				.of(Map.of("loc", List.of("query", failure.getName()), "msg", "Invalid value", "type", "enum"))));
	}

}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter extends OncePerRequestFilter {

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(RequestCorrelationFilter.class);

	private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String supplied = request.getHeader("X-Request-ID");
		String requestId = supplied != null && VALID_ID.matcher(supplied).matches() ? supplied
				: UUID.randomUUID().toString().replace("-", "");
		response.setHeader("X-Request-ID", requestId);
		response.setHeader("Cache-Control", "private, no-store");
		try (var context = MDC.putCloseable("request_id", requestId)) {
			long started = System.nanoTime();
			try {
				chain.doFilter(request, response);
			}
			finally {
				Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
				LOGGER.atInfo()
					.addKeyValue("http_method", request.getMethod())
					.addKeyValue("route", route == null ? "unmatched" : route.toString())
					.addKeyValue("http_status", response.getStatus())
					.addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
					.log("request_completed");
			}
		}
	}

}
