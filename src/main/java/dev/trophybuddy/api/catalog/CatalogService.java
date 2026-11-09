package dev.trophybuddy.api.catalog;

import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.ProviderHttp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CatalogService {

	private static final String ERROR = "catalog_unavailable";

	private static final Map<String, Integer> PLATFORMS = Map.of("PS3", 9, "PS4", 48, "PS5", 167, "Vita", 46, "PSVITA",
			46);

	private final JdbcClient jdbc;

	private final ProviderHttp http;

	private final ObjectMapper json;

	private final String clientId;

	private final String secret;

	private String accessToken;

	private Instant expires = Instant.EPOCH;

	CatalogService(JdbcClient jdbc, ProviderHttp http, ObjectMapper json, @Value("${IGDB_CLIENT_ID:}") String clientId,
			@Value("${IGDB_CLIENT_SECRET:}") String secret) {
		this.jdbc = jdbc;
		this.http = http;
		this.json = json;
		this.clientId = clientId;
		this.secret = secret;
	}

	/**
	 * Catalogue data is optional, including when its provider or cache is unavailable.
	 */
	public JsonNode find(String title, String platform) {
		var platforms = Arrays.stream(platform.split(","))
			.map(String::trim)
			.filter(PLATFORMS::containsKey)
			.map(PLATFORMS::get)
			.distinct()
			.sorted()
			.toList();
		if (platforms.isEmpty() || clientId.isBlank() || secret.isBlank()) {
			return null;
		}
		String normalized = normalize(title);
		String key = cacheKey(normalized, platforms);
		try {
			var cached = jdbc.sql("""
					SELECT coalesce(payload::text, 'null') FROM catalog_cache
					WHERE key = :key AND expires_at > now()
					""").param("key", key).query(String.class).optional();
			if (cached.isPresent()) {
				return json.readTree(cached.get());
			}
			var result = lookup(title, normalized, platforms);
			jdbc.sql("""
					INSERT INTO catalog_cache(key, payload, expires_at)
					VALUES (:key, CAST(:payload AS jsonb), now() + interval '1 day')
					ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, expires_at = excluded.expires_at
					""").param("key", key).param("payload", result == null ? null : result.toString()).update();
			return result;
		}
		catch (ApiFailure | DataAccessException | java.time.DateTimeException failure) {
			return null;
		}
	}

	private ObjectNode lookup(String title, String normalized, List<Integer> platforms) {
		String platformList = String.join(",", platforms.stream().map(String::valueOf).toList());
		String query = "search " + json.writeValueAsString(title.replaceAll("[™®©]", ""))
				+ "; fields name,slug,alternative_names.name,cover.image_id,first_release_date,"
				+ "involved_companies.developer,involved_companies.publisher,involved_companies.company.name,"
				+ "genres.name,platforms.id,platforms.name; where platforms = (" + platformList + "); limit 50;";
		List<JsonNode> candidates = new ArrayList<>();
		for (var row : request("games", query)) {
			var names = names(row.path("alternative_names"));
			names.add(row.path("name").asText(""));
			boolean platformMatch = false;
			for (var entry : row.path("platforms")) {
				platformMatch |= platforms.contains(entry.path("id").asInt(-1));
			}
			if (platformMatch && row.path("id").isIntegralNumber() && row.path("id").asInt() > 0
					&& row.path("slug").asText("").matches("[a-z0-9-]+")
					&& names.stream().map(CatalogService::normalize).anyMatch(normalized::equals)) {
				candidates.add(row);
			}
		}
		if (candidates.size() != 1) {
			return null;
		}
		var row = candidates.getFirst();
		int id = row.path("id").asInt();
		var cover = row.path("cover").path("image_id").asText("");
		var release = row.path("first_release_date");
		var result = json.createObjectNode()
			.put("id", id)
			.put("name", row.path("name").asText())
			.put("sourceUrl", "https://www.igdb.com/games/" + row.path("slug").asText())
			.put("coverUrl",
					cover.matches("[A-Za-z0-9_-]{1,128}")
							? "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/" + cover + ".jpg" : null)
			.put("releaseDate", release.isIntegralNumber() ? Instant.ofEpochSecond(release.asLong()).toString() : null)
			.putNull("mainStoryHours");
		result.set("developers", json.valueToTree(companies(row, "developer")));
		result.set("publishers", json.valueToTree(companies(row, "publisher")));
		result.set("genres", json.valueToTree(names(row.path("genres"))));
		result.set("platforms", json.valueToTree(names(row.path("platforms"))));
		try {
			var timing = request("game_time_to_beats",
					"fields game_id,hastily,count; where game_id = " + id + "; limit 2;");
			var matches = new ArrayList<JsonNode>();
			for (var entry : timing) {
				if (entry.path("game_id").asInt(-1) == id) {
					matches.add(entry);
				}
			}
			if (matches.size() == 1 && matches.getFirst().path("count").asInt() > 0
					&& matches.getFirst().path("hastily").asDouble() > 0) {
				result.put("mainStoryHours", matches.getFirst().path("hastily").asDouble() / 3600);
			}
		}
		catch (ApiFailure ignored) {
			// Main-story timing is independently optional.
		}
		return result;
	}

	private JsonNode request(String endpoint, String query) {
		for (int attempt = 0; attempt < 2; attempt++) {
			String token = token();
			var response = http.send("https://api.igdb.com/v4/" + endpoint,
					Map.of("Client-ID", clientId, "Authorization", "Bearer " + token, "Content-Type", "text/plain"),
					query, 5, ERROR);
			if (response.statusCode() == 401 && attempt == 0) {
				invalidate(token);
				continue;
			}
			if (response.statusCode() == 200) {
				var rows = http.parse(response.body(), ERROR);
				if (rows.isArray()) {
					return rows;
				}
			}
			throw new ApiFailure(ERROR);
		}
		throw new ApiFailure(ERROR);
	}

	private synchronized String token() {
		if (expires.isAfter(Instant.now())) {
			return accessToken;
		}
		var response = http.send("https://id.twitch.tv/oauth2/token",
				Map.of("Content-Type", "application/x-www-form-urlencoded"),
				ProviderHttp
					.form(Map.of("client_id", clientId, "client_secret", secret, "grant_type", "client_credentials")),
				5, ERROR);
		if (response.statusCode() != 200) {
			throw new ApiFailure(ERROR);
		}
		var body = http.parse(response.body(), ERROR);
		accessToken = body.path("access_token").asText("");
		if (accessToken.isBlank() || body.path("expires_in").asLong(0) <= 0) {
			throw new ApiFailure(ERROR);
		}
		expires = Instant.now().plusSeconds(Math.max(0, body.path("expires_in").asLong() - 60));
		return accessToken;
	}

	private synchronized void invalidate(String token) {
		if (token.equals(accessToken)) {
			expires = Instant.EPOCH;
		}
	}

	private static LinkedHashSet<String> names(JsonNode entries) {
		var values = new LinkedHashSet<String>();
		for (var entry : entries) {
			if (entry.path("name").isString()) {
				values.add(entry.path("name").asText());
			}
		}
		return values;
	}

	private static LinkedHashSet<String> companies(JsonNode row, String role) {
		var values = new LinkedHashSet<String>();
		for (var entry : row.path("involved_companies")) {
			if (entry.path(role).asBoolean(false) && entry.path("company").path("name").isString()) {
				values.add(entry.path("company").path("name").asText());
			}
		}
		return values;
	}

	private static String normalize(String title) {
		return Normalizer.normalize(title.replaceAll("[™®©]", ""), Normalizer.Form.NFKD)
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\p{M}+", "")
			.replaceAll("[^\\p{L}\\p{N}]+", " ")
			.trim();
	}

	private String cacheKey(String title, List<Integer> platforms) {
		try {
			String input = json.writeValueAsString(title) + ", " + platforms;
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(("[" + input + "]").getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
