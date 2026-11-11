package dev.trophybuddy.api.publicgames;

import dev.trophybuddy.api.support.ApiFailure;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/games")
class PublicGamesController {

	private static final int PAGE_SIZE = 24;

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final SpoilerArtwork artwork;

	PublicGamesController(JdbcClient jdbc, ObjectMapper json, SpoilerArtwork artwork) {
		this.jdbc = jdbc;
		this.json = json;
		this.artwork = artwork;
	}

	@GetMapping
	GamePage list(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String platform,
			@RequestParam(defaultValue = "") String coverage, @RequestParam(defaultValue = "1") int page) {
		if (query.length() > 120 || !platform.matches("|PS5|PS4|PS3|PSVITA") || !coverage.matches("|guide|trophies")
				|| page < 1 || page > 100000) {
			throw new ApiFailure("invalid_public_catalog_query", 400);
		}

		List<Game> games = jdbc.sql("""
				SELECT e.slug, e.title, e.platform, e.cover_url,
				    EXISTS (SELECT 1 FROM public_guide_revisions g WHERE g.slug = e.slug) AS has_guide
				FROM public_game_editions e
				WHERE strpos(lower(e.title), lower(:query)) > 0
				    AND (:platform = '' OR e.platform = :platform)
				    AND CASE :coverage
				        WHEN 'guide' THEN EXISTS (SELECT 1 FROM public_guide_revisions g WHERE g.slug = e.slug)
				        WHEN 'trophies' THEN NOT EXISTS (SELECT 1 FROM public_guide_revisions g WHERE g.slug = e.slug)
				        ELSE true END
				ORDER BY lower(e.title), e.slug LIMIT :limit OFFSET :offset
				""")
			.param("query", query.strip())
			.param("platform", platform)
			.param("coverage", coverage)
			.param("limit", PAGE_SIZE + 1)
			.param("offset", (page - 1) * PAGE_SIZE)
			.query((row, index) -> new Game(row.getString("slug"), row.getString("title"), row.getString("platform"),
					row.getString("cover_url"), row.getBoolean("has_guide")))
			.list();
		return new GamePage(games.size() > PAGE_SIZE ? games.subList(0, PAGE_SIZE) : games, games.size() > PAGE_SIZE);
	}

	/**
	 * Return only the spoiler-safe projection, including for anonymous clients and
	 * crawlers.
	 */
	@GetMapping("/{slug}")
	ObjectNode detail(@PathVariable String slug) {
		var result = jdbc.sql("""
				SELECT jsonb_build_object('slug', e.slug, 'title', e.title, 'platform', e.platform,
				    'coverUrl', e.cover_url, 'mainStoryHours', e.main_story_hours, 'guide', g.content,
				    'groups', coalesce((SELECT jsonb_agg(jsonb_build_object(
				        'id', t.group_id, 'name', t.name, 'imageUrl', t.image_url)
				        ORDER BY (t.group_id <> 'default'), t.group_id)
				        FROM trophy_groups t WHERE (t.service, t.set_id) = (e.service, e.set_id)), '[]'),
				    'trophies', coalesce((SELECT jsonb_agg(jsonb_build_object('id', t.trophy_id,
				        'groupId', t.group_id, 'type', t.type, 'hidden', t.hidden, 'earnedRate', t.global_earned_rate,
				        'name', CASE WHEN NOT t.hidden THEN t.name END,
				        'description', CASE WHEN NOT t.hidden THEN t.description END,
				        'imageUrl', CASE WHEN NOT t.hidden THEN t.image_url END) ORDER BY t.trophy_id)
				        FROM trophies t WHERE (t.service, t.set_id) = (e.service, e.set_id)), '[]'))::text
				FROM public_game_editions e
				LEFT JOIN LATERAL (
				    SELECT content FROM public_guide_revisions WHERE slug = e.slug ORDER BY revision DESC LIMIT 1
				) g ON true
				WHERE e.slug = :slug
				""")
			.param("slug", slug)
			.query(String.class)
			.optional()
			.map(this::object)
			.orElseThrow(PublicGamesController::missing);
		for (var item : result.path("guide").path("checkpoints")) {
			var checkpoint = (ObjectNode) item;
			boolean hasSpoiler = checkpoint.path("spoiler").isString();
			checkpoint.put("hasSpoiler", hasSpoiler);
			checkpoint.remove("spoiler");
			if (hasSpoiler) {
				checkpoint.remove("trophyIds");
			}
		}
		return result;
	}

	/**
	 * Return all concealed checkpoint details for an explicitly opened guide revision.
	 */
	@GetMapping("/{slug}/guides/{revision}/checkpoint-details")
	ObjectNode checkpointDetails(@PathVariable String slug, @PathVariable int revision,
			@RequestParam(defaultValue = "false") boolean includeTrophies) {
		return checkpointSpoilers(slug, revision, null, includeTrophies);
	}

	@GetMapping("/{slug}/guides/{revision}/checkpoints/{checkpointId}")
	ObjectNode spoiler(@PathVariable String slug, @PathVariable int revision, @PathVariable String checkpointId) {
		if (checkpointSpoilers(slug, revision, checkpointId, true).get(checkpointId) instanceof ObjectNode result) {
			return result;
		}
		throw missing();
	}

	/**
	 * Resolve referenced names from the same edition only after a checkpoint is opened.
	 */
	private ObjectNode checkpointSpoilers(String slug, int revision, String checkpointId, boolean includeTrophies) {
		return jdbc
			.sql("""
					SELECT coalesce((
					    SELECT jsonb_object_agg(checkpoint->>'id', CASE WHEN :includeTrophies THEN
					        jsonb_build_object('spoiler', checkpoint->>'spoiler', 'trophies', coalesce((
					            SELECT jsonb_agg(jsonb_build_object('id', t.trophy_id, 'name', t.name, 'type', t.type)
					                ORDER BY refs.position)
					            FROM jsonb_array_elements_text(checkpoint->'trophyIds') WITH ORDINALITY AS refs(id, position)
					            JOIN trophies t ON (t.service, t.set_id, t.trophy_id) = (e.service, e.set_id, refs.id::int)
					        ), '[]'::jsonb)) ELSE checkpoint->'spoiler' END)
					    FROM jsonb_array_elements(r.content->'checkpoints') AS checkpoint
					    WHERE jsonb_typeof(checkpoint->'spoiler') = 'string'
					        AND (CAST(:checkpoint AS text) IS NULL OR checkpoint->>'id' = :checkpoint)
					), '{}'::jsonb)::text
					FROM public_guide_revisions r JOIN public_game_editions e USING (slug)
					WHERE r.slug = :slug AND r.revision = :revision
					""")
			.param("slug", slug)
			.param("revision", revision)
			.param("checkpoint", checkpointId)
			.param("includeTrophies", includeTrophies)
			.query(String.class)
			.optional()
			.map(this::object)
			.orElseThrow(PublicGamesController::missing);
	}

	@GetMapping("/{slug}/trophies/{id}")
	ObjectNode trophy(@PathVariable String slug, @PathVariable int id) {
		return jdbc.sql("""
				SELECT jsonb_build_object('name', t.name, 'description', t.description, 'imageUrl', t.image_url)::text
				FROM public_game_editions e JOIN trophies t USING (service, set_id)
				WHERE e.slug = :slug AND t.trophy_id = :id AND t.hidden
				""")
			.param("slug", slug)
			.param("id", id)
			.query(String.class)
			.optional()
			.map(this::object)
			.orElseThrow(PublicGamesController::missing);
	}

	@GetMapping("/{slug}/trophies/{id}/artwork-preview")
	ResponseEntity<byte[]> artworkPreview(@PathVariable String slug, @PathVariable int id) {
		String imageUrl = jdbc.sql("""
				SELECT t.image_url FROM public_game_editions e JOIN trophies t USING (service, set_id)
				WHERE e.slug = :slug AND t.trophy_id = :id AND t.hidden AND t.image_url IS NOT NULL
				""")
			.param("slug", slug)
			.param("id", id)
			.query(String.class)
			.optional()
			.orElseThrow(PublicGamesController::missing);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
			.contentType(MediaType.IMAGE_PNG)
			.body(artwork.preview(imageUrl));
	}

	private ObjectNode object(String value) {
		return (ObjectNode) json.readTree(value);
	}

	private static ApiFailure missing() {
		return new ApiFailure("public_game_content_not_found", 404);
	}

	record Game(String slug, String title, String platform, String coverUrl, boolean hasGuide) {
	}

	record GamePage(List<Game> games, boolean hasNextPage) {
	}

}
