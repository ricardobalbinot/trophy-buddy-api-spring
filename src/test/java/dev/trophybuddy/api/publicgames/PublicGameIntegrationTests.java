package dev.trophybuddy.api.publicgames;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "TROPHY_VERIFY_WRITES", matches = "true")
class PublicGameIntegrationTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Test
	void anonymousCatalogKeepsSpoilersOutsideTheInitialPayload() throws Exception {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();
		if (!database.endsWith("_spring_test")) {
			throw new IllegalStateException("Write verification requires a dedicated _spring_test database");
		}

		jdbc.sql("INSERT INTO trophy_sets VALUES ('trophy2', 'NPWR999999998_00')").update();
		jdbc.sql("""
				INSERT INTO trophy_groups(service, set_id, group_id, name, observed_at)
				VALUES ('trophy2', 'NPWR999999998_00', 'default', 'Base', now())
				""").update();
		jdbc.sql("""
				INSERT INTO trophies(service, set_id, trophy_id, group_id, name, description, image_url, type,
				    hidden, observed_at)
				VALUES ('trophy2', 'NPWR999999998_00', 7, 'default', 'Secret Title', 'Secret Description',
				    'https://example.com/secret.png', 'silver', true, now()),
				    ('trophy2', 'NPWR999999998_00', 8, 'default', 'Visible Trophy', null,
				    null, 'bronze', false, now())
				""").update();
		jdbc.sql("""
				INSERT INTO public_game_editions (slug, service, set_id, title, platform)
				VALUES ('fixture-ps5', 'trophy2', 'NPWR999999998_00', 'Public Fixture', 'PS5')
				""").update();
		jdbc.sql("""
				INSERT INTO public_guide_revisions (slug, revision, content)
				VALUES ('fixture-ps5', 1, '{"revision": 1, "firstPlaythroughNote":"Play at your pace.", "checkpoints": [
				  {"id":"deadline","title":"Save before the deadline","spoiler":"Secret Checkpoint","trophyIds":[7,8]},
				  {"id":"later","title":"Save again","spoiler":"Unrelated Spoiler"},
				  {"id":"optional","title":"Take a break","trophyIds":[8]}
				], "routes": [{"id":"combined","playthroughs":1,"checkpointIds":["later","deadline"]}]}'::jsonb)
				""").update();

		mvc.perform(get("/api/v1/games").param("query", "public fixture").param("coverage", "guide"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.games[0].slug").value("fixture-ps5"));
		mvc.perform(get("/api/v1/games").param("query", "public fixture").param("coverage", "trophies"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.games").isEmpty());
		mvc.perform(get("/api/v1/games/fixture-ps5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.guide.checkpoints[0].hasSpoiler").value(true))
			.andExpect(jsonPath("$.guide.checkpoints[0].trophyIds").doesNotExist())
			.andExpect(jsonPath("$.guide.checkpoints[2].trophyIds[0]").value(8))
			.andExpect(jsonPath("$.guide.firstPlaythroughNote").value("Play at your pace."))
			.andExpect(jsonPath("$.guide.routes[0].playthroughs").value(1))
			.andExpect(jsonPath("$.guide.routes[0].checkpointIds[0]").value("later"))
			.andExpect(jsonPath("$.trophies[0].hidden").value(true))
			.andExpect(content().string(not(containsString("Secret"))))
			.andExpect(content().string(not(containsString("secret.png"))))
			.andExpect(content().string(not(containsString("Unrelated Spoiler"))));
		mvc.perform(get("/api/v1/games/fixture-ps5/guides/1/checkpoints/deadline"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.spoiler").value("Secret Checkpoint"))
			.andExpect(jsonPath("$.trophies[0].name").value("Secret Title"))
			.andExpect(jsonPath("$.trophies[0].type").value("silver"))
			.andExpect(jsonPath("$.trophies[1].id").value(8))
			.andExpect(content().string(not(containsString("Unrelated Spoiler"))));
		mvc.perform(get("/api/v1/games/fixture-ps5/guides/1/checkpoint-details"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deadline").value("Secret Checkpoint"))
			.andExpect(jsonPath("$.later").value("Unrelated Spoiler"))
			.andExpect(content().string(not(containsString("Secret Title"))));
		mvc.perform(get("/api/v1/games/fixture-ps5/guides/1/checkpoint-details").param("includeTrophies", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deadline.spoiler").value("Secret Checkpoint"))
			.andExpect(jsonPath("$.deadline.trophies[0].name").value("Secret Title"))
			.andExpect(jsonPath("$.later.trophies").isEmpty())
			.andExpect(jsonPath("$.optional").doesNotExist());
		mvc.perform(get("/api/v1/games/fixture-ps5/guides/2/checkpoint-details")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/games/fixture-ps5/trophies/7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Secret Title"));
		mvc.perform(get("/api/v1/games/missing")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/library")).andExpect(status().isUnauthorized());
	}

}
