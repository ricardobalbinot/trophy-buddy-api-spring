package dev.trophybuddy.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlEnvironmentTests {

	@Test
	void convertsPlatformUriWithoutPuttingCredentialsInJdbcUrl() {
		var properties = DatabaseUrlEnvironment
			.properties("postgresql://app:p%40ss+word@postgres.railway.internal:5432/railway?sslmode=require");
		assertThat(properties)
			.containsEntry("JDBC_DATABASE_URL",
					"jdbc:postgresql://postgres.railway.internal:5432/railway?sslmode=require")
			.containsEntry("DB_USER", "app")
			.containsEntry("DB_PASSWORD", "p@ss+word");
	}

	@Test
	void omitsSecretsFromErrors() {
		assertThatThrownBy(() -> DatabaseUrlEnvironment.properties("postgresql://user:secret@[broken"))
			.isInstanceOf(IllegalStateException.class)
			.hasNoCause()
			.hasMessage("DATABASE_URL must be a PostgreSQL URI with credentials");
	}

}
