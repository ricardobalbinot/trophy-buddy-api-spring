package dev.trophybuddy.api;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

	private final JdbcClient jdbc;

	HealthController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/healthz")
	Map<String, String> health() {
		return Map.of("status", "ok", "service", "trophy-buddy-api");
	}

	@GetMapping("/readyz")
	Map<String, String> readiness() {
		jdbc.sql("SELECT uid FROM libraries LIMIT 0").query(String.class).list();
		return Map.of("status", "ready");
	}

}
