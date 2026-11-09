package dev.trophybuddy.api.support;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Adapt the platform's PostgreSQL URI while keeping credentials out of JDBC URLs. */
public class DatabaseUrlEnvironment implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String databaseUrl = environment.getProperty("DATABASE_URL");
		if (databaseUrl == null || databaseUrl.isBlank() || environment.containsProperty("JDBC_DATABASE_URL")) {
			return;
		}
		environment.getPropertySources().addLast(new MapPropertySource("platformDatabase", properties(databaseUrl)));
	}

	static Map<String, Object> properties(String databaseUrl) {
		try {
			URI uri = URI.create(databaseUrl);
			String userInfo = uri.getRawUserInfo();
			String path = uri.getRawPath();
			if (!("postgres".equals(uri.getScheme()) || "postgresql".equals(uri.getScheme())) || uri.getHost() == null
					|| userInfo == null || path == null || path.length() < 2 || uri.getRawFragment() != null) {
				throw new IllegalArgumentException();
			}
			String[] credentials = userInfo.split(":", 2);
			if (credentials.length != 2 || credentials[0].isBlank()) {
				throw new IllegalArgumentException();
			}
			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
			return Map.of("JDBC_DATABASE_URL", "jdbc:postgresql://" + uri.getHost() + port + path + query, "DB_USER",
					decode(credentials[0]), "DB_PASSWORD", decode(credentials[1]));
		}
		catch (IllegalArgumentException failure) {
			throw new IllegalStateException("DATABASE_URL must be a PostgreSQL URI with credentials");
		}
	}

	private static String decode(String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

}
