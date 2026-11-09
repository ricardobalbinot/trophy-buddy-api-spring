package dev.trophybuddy.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TrophyBuddyApiSpringApplicationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void healthPreservesThePublicContract() throws Exception {
		mvc.perform(get("/healthz").header("X-Request-ID", "local-smoke-test"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Request-ID", "local-smoke-test"))
			.andExpect(jsonPath("$.status").value("ok"))
			.andExpect(jsonPath("$.service").value("trophy-buddy-api"));
	}

	@Test
	void documentsTheMigratedPublicAndPrivateRoutes() throws Exception {
		mvc.perform(get("/openapi.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/v1/library/sync'].post").exists())
			.andExpect(jsonPath("$.paths['/api/v1/library/games/{game_id}'].post").exists())
			.andExpect(jsonPath("$.paths['/api/v1/catalog'].get").exists());
	}

	@Test
	void anonymousRequestsCannotReadPrivateLibrary() throws Exception {
		mvc.perform(get("/api/v1/library"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"))
			.andExpect(jsonPath("$.error").value("authentication_required"));
	}

	@Test
	@WithMockUser
	void rejectsUnrecognizedSortBeforeQueryingTheDatabase() throws Exception {
		mvc.perform(get("/api/v1/library").param("sort", "unsupported"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail[0].loc[1]").value("sort"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "TROPHY_VERIFY_EXISTING_LIBRARY", matches = "true")
	void readsAnExistingPostgresLibraryWithoutExposingItToAnotherUid() throws Exception {
		String uid = jdbc.sql("SELECT uid FROM libraries ORDER BY uid LIMIT 1").query(String.class).single();
		int expectedCount = jdbc.sql("""
				SELECT count(*) FROM library_games
				WHERE uid = :uid AND trophy_set_id <> ''
				""").param("uid", uid).query(Integer.class).single();

		mvc.perform(get("/api/v1/library").with(user(uid)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshot.version").value(1))
			.andExpect(jsonPath("$.snapshot.games.length()").value(expectedCount));
		mvc.perform(get("/api/v1/library/statistics").with(user(uid)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalGames").value(expectedCount));
		mvc.perform(get("/api/v1/library").with(user(java.util.UUID.randomUUID().toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshot").value(org.hamcrest.Matchers.nullValue()));
	}

}