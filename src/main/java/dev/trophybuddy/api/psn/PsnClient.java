package dev.trophybuddy.api.psn;

import dev.trophybuddy.api.library.LibrarySnapshot;
import dev.trophybuddy.api.library.LibrarySnapshot.Game;
import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.ProviderHttp;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PsnClient {

	private static final String BASE = "https://m.np.playstation.com/api/";

	private static final String ERROR = "psn_data_unavailable";

	private static final Set<String> GRADES = Set.of("bronze", "silver", "gold", "platinum");

	private final PsnSession sessions;

	private final ProviderHttp http;

	private final ObjectMapper json;

	PsnClient(PsnSession sessions, ProviderHttp http, ObjectMapper json) {
		this.sessions = sessions;
		this.http = http;
		this.json = json;
	}

	public record Trophy(int id, String groupId, String name, String description, String imageUrl, String type,
			boolean hidden, Boolean earned, String earnedAt, Double earnedRate) {
	}

	public record Group(String id, String name) {
	}

	public record Observations(List<Trophy> trophies, List<Group> groups) {
	}

	public LibrarySnapshot importLibrary(String onlineId) {
		var token = sessions.get();
		String account = resolveAccount(token, onlineId);
		JsonNode profile = request(token, "userProfile/v1/internal/users/" + account + "/profiles", Map.of(), null);
		if (!onlineId.equalsIgnoreCase(profile.path("onlineId").asText())) {
			throw new ApiFailure("psn_profile_not_found", 404);
		}
		String trophyPath = "trophy/v1/users/" + account;
		var titles = pages(token, trophyPath + "/trophyTitles", "trophyTitles", Map.of());
		var played = pages(token, "gamelist/v2/users/" + account + "/titles", "titles", Map.of());
		var links = titleLinks(token, trophyPath, played);
		var identity = new LibrarySnapshot.Profile(account, profile.path("onlineId").asText(), avatar(profile),
				summary(token, trophyPath, account));

		return new LibrarySnapshot(1, identity, timestamp(), mapLibrary(titles, played, links));
	}

	private String resolveAccount(PsnSession.Token token, String onlineId) {
		if (onlineId.equalsIgnoreCase(token.onlineId())) {
			return token.accountId();
		}
		String body = json.writeValueAsString(
				Map.of("searchTerm", onlineId, "domainRequests", List.of(Map.of("domain", "SocialAllAccounts"))));
		JsonNode domains = request(token, "search/v1/universalSearch", Map.of(), body).path("domainResponses");
		if (!domains.isArray()) {
			throw new ApiFailure("psn_search_unavailable");
		}
		Set<String> matches = new HashSet<>();
		for (var domain : domains) {
			for (var item : domain.path("results")) {
				var profile = item.path("socialMetadata");
				String account = profile.path("accountId").asText("");
				if (onlineId.equalsIgnoreCase(profile.path("onlineId").asText()) && account.matches("[0-9]+")) {
					matches.add(account);
				}
			}
		}
		if (matches.size() != 1) {
			throw new ApiFailure("psn_profile_not_found", 404);
		}
		return matches.iterator().next();
	}

	private Map<String, List<String>> titleLinks(PsnSession.Token token, String path, List<JsonNode> played) {
		var ids = played.stream()
			.map(row -> required(row, "titleId"))
			.filter(id -> id.matches("(?:CUSA|PPSA)[0-9]+_[0-9]{2}"))
			.distinct()
			.toList();
		Map<String, List<String>> links = new HashMap<>();
		for (int offset = 0; offset < ids.size(); offset += 5) {
			String batch = String.join(",", ids.subList(offset, Math.min(offset + 5, ids.size())));
			var entries = request(token, path + "/titles/trophyTitles", Map.of("npTitleIds", batch), null)
				.path("titles");
			if (!entries.isArray()) {
				throw new ApiFailure("psn_mapping_unavailable");
			}
			for (var entry : entries) {
				var sets = new ArrayList<String>();
				for (var set : entry.path("trophyTitles")) {
					sets.add(required(set, "npCommunicationId"));
				}
				links.put(required(entry, "npTitleId"), sets);
			}
		}
		return links;
	}

	static List<Game> mapLibrary(List<JsonNode> titles, List<JsonNode> played, Map<String, List<String>> links) {
		Map<String, Game> games = new LinkedHashMap<>();
		for (var title : titles) {
			String set = required(title, "npCommunicationId");
			String id = "trophy-" + set;
			if (!set.matches("NPWR[0-9]+_[0-9]{2}") || games.containsKey(id)) {
				throw new ApiFailure("invalid_psn_identity");
			}
			var earned = title.path("earnedTrophies");
			var progress = title.path("progress");
			games.put(id,
					new Game(id, required(title, "trophyTitleName"), required(title, "trophyTitlePlatform"),
							artwork(title.path("trophyTitleIconUrl").asText("")), new ArrayList<>(), set,
							text(title, "npServiceName"), sum(earned), sum(title.path("definedTrophies")),
							progress.isIntegralNumber() ? Math.clamp(progress.asInt(), 0, 100) : null,
							earned.path("platinum").asInt(0) > 0, null, date(text(title, "lastUpdatedDateTime"))));
		}
		for (var row : played) {
			String id = required(row, "titleId");
			Instant playedAt = date(text(row, "lastPlayedDateTime"));
			var matches = links.getOrDefault(id, List.of())
				.stream()
				.map(set -> "trophy-" + set)
				.filter(games::containsKey)
				.distinct()
				.toList();
			if (matches.isEmpty()) {
				String platform = Map.of("ps4_game", "PS4", "ps5_native_game", "PS5", "pspc_game", "PC")
					.getOrDefault(row.path("category").asText(""), "unknown");
				games.put("played-" + id,
						new Game("played-" + id, required(row, "name"), platform,
								artwork(row.path("imageUrl").asText("")), List.of(id), null, null, null, null, null,
								false, playedAt, null));
			}
			for (String match : matches) {
				Game game = games.get(match);
				if (!game.titleIds().contains(id)) {
					game.titleIds().add(id);
				}
				if (playedAt != null && (game.lastPlayedAt() == null || playedAt.isAfter(game.lastPlayedAt()))) {
					games.put(match,
							new Game(game.id(), game.title(), game.platform(), game.imageUrl(), game.titleIds(),
									game.trophySetId(), game.trophyService(), game.earned(), game.total(),
									game.progress(), game.platinum(), playedAt, game.trophyProgressUpdatedAt()));
				}
			}
		}
		return games.values()
			.stream()
			.sorted(Comparator.comparing(Game::lastPlayedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Game::id))
			.toList();
	}

	public Observations trophies(Game game, String account) {
		var token = sessions.get();
		var params = Map.of("npServiceName", service(game));
		String suffix = "/npCommunicationIds/" + game.trophySetId() + "/trophyGroups";
		String publicPath = "trophy/v1" + suffix;
		var definitions = pages(token, publicPath + "/all/trophies", "trophies", params);
		Map<Integer, JsonNode> earnings = new HashMap<>();
		List<Group> groups = new ArrayList<>();
		try {
			for (var item : pages(token, "trophy/v1/users/" + account + suffix + "/all/trophies", "trophies", params)) {
				earnings.put(item.path("trophyId").asInt(-1), item);
			}
		}
		catch (ApiFailure ignored) {
			earnings.clear();
		}
		try {
			for (var item : request(token, publicPath, params, null).path("trophyGroups")) {
				groups.add(new Group(required(item, "trophyGroupId"), required(item, "trophyGroupName")));
			}
		}
		catch (ApiFailure ignored) {
			groups.clear();
		}
		var seen = new HashSet<Integer>();
		var trophies = new ArrayList<Trophy>();
		for (var row : definitions) {
			int id = row.path("trophyId").asInt(-1);
			String grade = row.path("trophyType").asText("");
			if (!row.path("trophyId").isIntegralNumber() || id < 0 || !seen.add(id) || !GRADES.contains(grade)) {
				throw new ApiFailure("invalid_psn_trophy");
			}
			var entry = earnings.get(id);
			var flag = entry == null ? null : entry.path("earned");
			Boolean earned = flag != null && flag.isBoolean() ? flag.asBoolean() : null;
			var earnedAt = Boolean.TRUE.equals(earned) ? date(text(entry, "earnedDateTime")) : null;
			String group = row.path("trophyGroupId").asText("default");
			trophies.add(new Trophy(id, group.isBlank() ? "default" : group, text(row, "trophyName"),
					text(row, "trophyDetail"), artwork(row.path("trophyIconUrl").asText("")), grade,
					row.path("trophyHidden").isBoolean() && row.path("trophyHidden").asBoolean(), earned,
					earnedAt == null ? null : earnedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
					entry == null ? null : rate(entry.path("trophyEarnedRate").asText(""))));
		}
		return new Observations(trophies, groups);
	}

	public static String service(Game game) {
		if (game.trophyService() != null) {
			return game.trophyService();
		}
		return List.of(game.platform().split(",")).contains("PS5") ? "trophy2" : "trophy";
	}

	private List<JsonNode> pages(PsnSession.Token token, String path, String collection, Map<String, String> params) {
		var result = new ArrayList<JsonNode>();
		int offset = 0;
		long deadline = System.nanoTime() + 180_000_000_000L;
		for (int page = 0; page < 100; page++) {
			if (System.nanoTime() > deadline) {
				throw new ApiFailure("psn_import_timeout");
			}
			var query = new HashMap<>(params);
			query.put("offset", String.valueOf(offset));
			query.put("limit", "100");
			var payload = request(token, path, query, null);
			var rows = payload.path(collection);
			var total = payload.path("totalItemCount");
			if (!rows.isArray() || !total.isIntegralNumber() || total.asInt(-1) < 0) {
				throw new ApiFailure("invalid_psn_page");
			}
			for (var row : rows) {
				if (!row.isObject()) {
					throw new ApiFailure("invalid_psn_page");
				}
				result.add(row);
			}
			var next = payload.path("nextOffset");
			if (next.isMissingNode() || next.isNull()) {
				if (result.size() != total.asInt()) {
					throw new ApiFailure("incomplete_psn_page");
				}
				return result;
			}
			if (!next.isIntegralNumber() || next.asInt() <= offset || rows.isEmpty()) {
				throw new ApiFailure("invalid_psn_pagination");
			}
			offset = next.asInt();
		}
		throw new ApiFailure("psn_pagination_limit");
	}

	private JsonNode request(PsnSession.Token token, String path, Map<String, String> params, String body) {
		String url = BASE + path + (params.isEmpty() ? "" : "?" + ProviderHttp.form(params));
		var headers = Map.of("Authorization", "Bearer " + token.accessToken(), "Accept-Language", "pt-BR",
				"Content-Type", "application/json");
		var response = http.send(url, headers, body, 15, ERROR);
		if (response.statusCode() >= 500) {
			response = http.send(url, headers, body, 15, ERROR);
		}
		if (response.statusCode() == 401) {
			throw new ApiFailure("psn_reconnect_required");
		}
		if (response.statusCode() == 429) {
			throw new ApiFailure("psn_rate_limited", 429);
		}
		if (response.statusCode() != 200) {
			throw new ApiFailure(ERROR);
		}
		JsonNode result = http.parse(response.body(), ERROR);
		if (!result.isObject() || result.hasNonNull("error")) {
			throw new ApiFailure(ERROR);
		}
		return result;
	}

	private LibrarySnapshot.Summary summary(PsnSession.Token token, String path, String account) {
		try {
			var row = request(token, path + "/trophySummary", Map.of(), null);
			var earned = row.path("earnedTrophies");
			int level = row.path("trophyLevel").asInt(-1);
			int progress = row.path("progress").asInt(-1);
			if (!account.equals(row.path("accountId").asText()) || level < 1 || level > 999 || progress < 0
					|| progress > 100) {
				return null;
			}
			return new LibrarySnapshot.Summary(level, progress, new LibrarySnapshot.Counts(count(earned.path("bronze")),
					count(earned.path("silver")), count(earned.path("gold")), count(earned.path("platinum"))));
		}
		catch (ApiFailure ignored) {
			return null;
		}
	}

	private static String avatar(JsonNode profile) {
		for (var avatar : profile.path("avatars")) {
			String url = avatar.path("url")
				.asText("")
				.replaceFirst("^http://static-resource\\.np\\.community\\.playstation\\.net/",
						"https://static-resource.np.community.playstation.net/");
			String valid = artwork(url);
			if (valid != null) {
				return valid;
			}
		}
		return null;
	}

	private static String artwork(String url) {
		try {
			URI uri = URI.create(url);
			return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? url : null;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static int count(JsonNode value) {
		if (!value.isIntegralNumber() || value.asInt(-1) < 0) {
			throw new ApiFailure(ERROR);
		}
		return value.asInt();
	}

	private static int sum(JsonNode values) {
		if (!values.isObject()) {
			throw new ApiFailure(ERROR);
		}
		int total = 0;
		for (var value : values) {
			total = Math.addExact(total, count(value));
		}
		return total;
	}

	private static Double rate(String value) {
		try {
			double parsed = Double.parseDouble(value);
			return parsed >= 0 && parsed <= 100 ? parsed : null;
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String text(JsonNode row, String field) {
		String value = row.path(field).asText("");
		return value.isEmpty() ? null : value;
	}

	private static String required(JsonNode row, String field) {
		String value = text(row, field);
		if (value == null) {
			throw new ApiFailure(ERROR);
		}
		return value;
	}

	private static Instant date(String value) {
		try {
			return value == null ? null : OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	public static String timestamp() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
	}

}
