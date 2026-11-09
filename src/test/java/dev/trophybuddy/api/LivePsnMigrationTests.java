package dev.trophybuddy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TROPHY_VERIFY_LIVE_PSN", matches = "true")
class LivePsnMigrationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private Environment environment;

	@Test
	void syncsAPublicProfileAndLoadsATrophyListThroughTheSpringRoutes() throws Exception {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();
		assertThat(database).endsWith("_spring_test");
		String target = environment.getRequiredProperty("TROPHY_TEST_ONLINE_ID");
		assertThat(target).matches("[a-zA-Z][a-zA-Z0-9_-]{2,15}");
		String uid = "spring-live-test-" + UUID.randomUUID();

		try {
			var result = mvc
				.perform(post("/api/v1/library/sync").with(user(uid))
					.contentType(MediaType.APPLICATION_JSON)
					.content(json.createObjectNode().put("onlineId", target).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.snapshot.games").isNotEmpty())
				.andReturn();
			var games = json.readTree(result.getResponse().getContentAsString()).path("snapshot").path("games");
			String gameId = games.get(0).path("id").asText();
			mvc.perform(post("/api/v1/library/games/{id}", gameId).with(user(uid))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.details.trophyStatus").value("available"))
				.andExpect(jsonPath("$.details.trophies").isNotEmpty());
		}
		finally {
			jdbc.sql("DELETE FROM users WHERE uid = :uid").param("uid", uid).update();
		}
	}

}
