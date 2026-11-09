package dev.trophybuddy.api.guides;

import dev.trophybuddy.api.library.LibrarySnapshot.Game;
import dev.trophybuddy.api.psn.PsnClient;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GuideDiscovery {

	private static final Map<String, Integer> FLAGS = Map.of("PS3", 128, "PS4", 256, "PS5", 512, "PSVITA", 1024, "PC",
			2048);

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	public GuideDiscovery(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Resolve against the persisted index and retain stale bindings for manual review.
	 */
	public void attach(Game game, ObjectNode details) {
		String service = PsnClient.service(game);
		String set = game.trophySetId();
		details.putNull("guide").put("guideAnalysis", "not_requested");
		jdbc.sql("INSERT INTO trophy_sets(service, set_id) VALUES (:service, :set) ON CONFLICT DO NOTHING")
			.param("service", service)
			.param("set", set)
			.update();
		jdbc.sql("""
				INSERT INTO guide_discoveries(service, set_id, state, retry_at)
				VALUES (:service, :set, 'pending', now()) ON CONFLICT DO NOTHING
				""").param("service", service).param("set", set).update();
		var discovery = jdbc.sql("""
				SELECT state, retry_at > now() FROM guide_discoveries
				WHERE service = :service AND set_id = :set FOR UPDATE
				""")
			.param("service", service)
			.param("set", set)
			.query((row, index) -> new Discovery(row.getString(1), row.getBoolean(2)))
			.single();
		var guide = load(game);
		if (guide == null) {
			boolean hasBinding = jdbc.sql("""
					SELECT EXISTS(SELECT 1 FROM guide_bindings WHERE service = :service AND set_id = :set)
					""").param("service", service).param("set", set).query(Boolean.class).single();
			if (hasBinding || discovery.waiting()) {
				details.put("guideDiscovery", hasBinding ? "review_required" : discovery.state());
				return;
			}
			var candidates = jdbc.sql("""
					WITH ranked AS (
					  SELECT entry_id, dense_rank() OVER (ORDER BY CASE source
					      WHEN 'psnprofiles' THEN 1 WHEN 'powerpyx' THEN 2 WHEN 'playstationtrophies' THEN 3
					      WHEN 'platget' THEN 4 WHEN 'knoef' THEN 5 ELSE 6 END) AS priority
					  FROM indexed_guides WHERE active AND game_name = :title AND trophy_count = :total
					    AND (attributes & :flag) <> 0
					    AND source IN ('psnprofiles', 'powerpyx', 'playstationtrophies', 'platget', 'knoef')
					)
					SELECT entry_id FROM ranked WHERE priority = 1 ORDER BY entry_id LIMIT 2
					""")
				.param("title", normalize(game.title()))
				.param("total", game.total())
				.param("flag", FLAGS.getOrDefault(game.platform(), 0))
				.query(String.class)
				.list();
			if (candidates.size() != 1) {
				String state = candidates.isEmpty() ? "not_found" : "ambiguous";
				state(service, set, state, 6);
				details.put("guideDiscovery", state);
				return;
			}
			jdbc.sql("""
					INSERT INTO guide_bindings(service, set_id, entry_id, version_digest, content_digest,
					    game_title, platform, reviewed_at, rationale, match_method)
					SELECT :service, :set, entry_id, version_digest, content_digest, :title, :platform, now(),
					    'Automatic exact edition, platform and trophy-count match; unique source candidate.',
					    'automatic'
					FROM indexed_guides WHERE entry_id = :entry AND active
					ON CONFLICT DO NOTHING
					""")
				.param("service", service)
				.param("set", set)
				.param("title", game.title())
				.param("platform", game.platform())
				.param("entry", candidates.getFirst())
				.update();
			guide = load(game);
			if (guide == null) {
				details.put("guideDiscovery", "review_required");
				return;
			}
		}
		state(service, set, "matched", 0);
		jdbc.sql(
				"""
						INSERT INTO guide_analysis_requests(entry_id, content_digest, schema_version, state, reason, requested_at)
						SELECT entry_id, content_digest, '1', 'blocked', 'analysis_worker_not_configured', now()
						FROM guide_bindings WHERE service = :service AND set_id = :set ON CONFLICT DO NOTHING
						""")
			.param("service", service)
			.param("set", set)
			.update();
		String analysis = jdbc.sql("""
				SELECT a.state FROM guide_analysis_requests a JOIN guide_bindings b USING(entry_id, content_digest)
				WHERE b.service = :service AND b.set_id = :set AND a.schema_version = '1'
				""").param("service", service).param("set", set).query(String.class).single();
		details.set("guide", guide);
		details.put("guideDiscovery", "matched").put("guideAnalysis", analysis);
	}

	private record Discovery(String state, boolean waiting) {
	}

	private void state(String service, String set, String state, int hours) {
		jdbc.sql("""
				UPDATE guide_discoveries SET state = :state, retry_at = now() + :hours * interval '1 hour'
				WHERE service = :service AND set_id = :set
				""").param("service", service).param("set", set).param("state", state).param("hours", hours).update();
	}

	private tools.jackson.databind.JsonNode load(Game game) {
		return jdbc.sql("""
				SELECT jsonb_build_object(
				  'matchMethod', b.match_method, 'title', i.title, 'sourceName', i.source_name, 'sourceUrl', i.url,
				  'indexUrl', 'https://github.com/langdonx/trophy-guide-list-json', 'retrievedAt', v.retrieved_at,
				  'reviewedAt', b.reviewed_at, 'difficulty', i.difficulty, 'playthroughs', i.playthroughs,
				  'platinumHours', i.platinum_hours,
				  'missables', CASE WHEN (i.attributes & 16384) <> 0 THEN 'confirmed_some' ELSE 'unknown' END,
				  'online', CASE WHEN (i.attributes & 32768) <> 0 THEN 'confirmed_some' ELSE 'unknown' END,
				  'buggy', CASE WHEN (i.attributes & 8192) <> 0 THEN 'confirmed_some' ELSE 'unknown' END
				)::text
				FROM guide_bindings b JOIN indexed_guides i ON i.entry_id = b.entry_id
				JOIN guide_index_versions v ON v.digest = i.version_digest
				WHERE b.service = :service AND b.set_id = :set AND b.content_digest = i.content_digest
				  AND i.active AND b.platform = :platform AND i.trophy_count = :total
				""")
			.param("service", PsnClient.service(game))
			.param("set", game.trophySetId())
			.param("platform", game.platform())
			.param("total", game.total())
			.query(String.class)
			.optional()
			.map(json::readTree)
			.orElse(null);
	}

	static String normalize(String title) {
		return title.replaceFirst("(?i)\\s+trophy guide$", "")
			.replaceAll("\\s+", " ")
			.strip()
			.toLowerCase(Locale.ROOT)
			.replace("™", "")
			.replace("®", "");
	}

}
