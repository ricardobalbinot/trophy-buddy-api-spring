package dev.trophybuddy.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.trophybuddy.api.library.LibrarySnapshot;
import dev.trophybuddy.api.psn.PsnClient;
import dev.trophybuddy.api.support.ApiFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TROPHY_VERIFY_WRITES", matches = "true")
class MigrationIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private PsnClient psn;

	@Test
	void importsPublishesDetailsAndPreservesTheLastGoodSnapshot() throws Exception {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();
		if (!database.endsWith("_spring_test")) {
			throw new IllegalStateException("Write verification requires a dedicated _spring_test database");
		}

		String uid = UUID.randomUUID().toString();
		String account = String.valueOf(System.nanoTime()).replace("-", "");
		String set = "NPWR" + account + "_00";
		var game = new LibrarySnapshot.Game("trophy-" + set, "Migration Test Edition " + account, "PS5", null,
				List.of("PPSA12345_00"), set, "trophy2", 1, 1, 100, true, Instant.parse("2026-01-01T00:00:00Z"), null);
		String syncedAt = PsnClient.timestamp();
		var profile = new LibrarySnapshot.Profile(account, "PublicPlayer", "https://example.org/avatar.png",
				new LibrarySnapshot.Summary(10, 15, new LibrarySnapshot.Counts(0, 0, 0, 1)));
		var imported = new LibrarySnapshot(1, profile, syncedAt, List.of(game));
		var trophy = new PsnClient.Trophy(0, "default", "Winner", "Finish the game", null, "platinum", false, true,
				"2026-01-01T00:00:00Z", 10.5);
		when(psn.importLibrary("PublicPlayer")).thenReturn(imported).thenThrow(new ApiFailure("incomplete_psn_page"));
		when(psn.trophies(game, account)).thenReturn(
				new PsnClient.Observations(List.of(trophy), List.of(new PsnClient.Group("default", "Base Game"))));
		seedGuide(game);

		mvc.perform(post("/api/v1/library/sync").with(user(uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"onlineId\":\"PublicPlayer\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshot.syncedAt").value(syncedAt));
		mvc.perform(post("/api/v1/library/sync").with(user(uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"onlineId\":\"PublicPlayer\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "15"));
		mvc.perform(post("/api/v1/library/games/{id}", game.id()).with(user(uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.details.trophies[0].earned").value(true))
			.andExpect(jsonPath("$.details.trophies[0].guideEvidence").isEmpty())
			.andExpect(jsonPath("$.details.groups[0].name").value("Base Game"))
			.andExpect(jsonPath("$.details.guide.matchMethod").value("automatic"))
			.andExpect(jsonPath("$.details.guideAnalysis").value("blocked"));

		seedResource(set);
		mvc.perform(post("/api/v1/library/games/{id}", game.id()).with(user(uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refresh\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.details.trophies[0].resources[0].title").value("Published tip"))
			.andExpect(jsonPath("$.details.trophies[0].resources.length()").value(1));
		mvc.perform(post("/api/v1/library/games/{id}", game.id()).with(user("other-" + uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isNotFound());

		jdbc.sql("UPDATE operation_leases SET retry_after = 0 WHERE resource = :key")
			.param("key", "library:" + uid)
			.update();
		mvc.perform(post("/api/v1/library/sync").with(user(uid))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"onlineId\":\"PublicPlayer\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("incomplete_psn_page"));
		mvc.perform(get("/api/v1/library").with(user(uid)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshot.syncedAt").value(syncedAt))
			.andExpect(jsonPath("$.snapshot.profile.trophySummary.earnedTrophies.platinum").value(1));
	}

	private void seedGuide(LibrarySnapshot.Game game) {
		String digest = "0".repeat(64);
		jdbc.sql("""
				INSERT INTO guide_index_versions(digest, source_url, retrieved_at)
				VALUES (:digest, 'https://example.org/index', now()) ON CONFLICT DO NOTHING
				""").param("digest", digest).update();
		jdbc.sql("""
				INSERT INTO indexed_guides(entry_id, version_digest, content_digest, active, source,
				    source_name, title, game_name, url, attributes, external_game_ids, authors, trophy_count)
				VALUES (:id, :digest, :digest, true, 'psnprofiles', 'PSNProfiles', :title, lower(:title),
				    'https://example.org/guide', 512, '[]', '[]', 1)
				""").param("id", game.trophySetId()).param("digest", digest).param("title", game.title()).update();
	}

	private void seedResource(String set) {
		for (boolean published : List.of(true, false)) {
			jdbc.sql("""
					INSERT INTO trophy_resources(resource_id, service, set_id, trophy_id, title, source_name,
					    source_url, kind, language, contains_spoilers, published, reviewed_at)
					VALUES (:id, 'trophy2', :set, 0, :title, 'Example', 'https://example.org/tip',
					    'article', 'en', false, :published, now())
					""")
				.param("id", UUID.randomUUID().toString())
				.param("set", set)
				.param("title", published ? "Published tip" : "Draft tip")
				.param("published", published)
				.update();
		}
	}

}
