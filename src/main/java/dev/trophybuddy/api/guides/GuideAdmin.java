package dev.trophybuddy.api.guides;

import dev.trophybuddy.api.support.OperationLeases;
import dev.trophybuddy.api.support.ProviderHttp;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication
@ConditionalOnProperty("trophybuddy.guide-admin")
class GuideAdmin implements ApplicationRunner {

	private static final String INDEX = "https://raw.githubusercontent.com/langdonx/trophy-guide-list-json/main/guides-v2.min.json";

	private static final Map<Integer, Source> SOURCES = Map.of(1,
			new Source("psnprofiles", "PSNProfiles", "https://psnprofiles.com/guide/"), 2,
			new Source("knoef", "Knoef", "https://knoef.info/"), 4,
			new Source("platget", "PlatGet", "https://www.platget.com/"), 8,
			new Source("playstationtrophies", "PlayStationTrophies", "https://www.playstationtrophies.org/game/"), 16,
			new Source("powerpyx", "PowerPyx", "https://powerpyx.com/"));

	private static final Map<String, Integer> FLAGS = Map.of("PS3", 128, "PS4", 256, "PS5", 512, "PSVITA", 1024, "PC",
			2048);

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final ProviderHttp http;

	private final TransactionTemplate transactions;

	private final OperationLeases leases;

	private final GuideEvidenceImport evidence;

	GuideAdmin(JdbcClient jdbc, ObjectMapper json, ProviderHttp http, TransactionTemplate transactions,
			OperationLeases leases, GuideEvidenceImport evidence) {
		this.jdbc = jdbc;
		this.json = json;
		this.http = http;
		this.transactions = transactions;
		this.leases = leases;
		this.evidence = evidence;
	}

	private record Source(String id, String name, String root) {
	}

	private record Entry(String id, String digest, String source, String sourceName, String title, String gameName,
			String url, int attributes, JsonNode gameIds, JsonNode authors, Double difficulty, Double playthroughs,
			Double hours, Integer trophyCount) {
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		switch (arg(args, "trophybuddy.guide-admin")) {
			case "import-index" -> importIndex();
			case "list" -> list(arg(args, "game"));
			case "bind" -> bind(args);
			case "add-resource" -> resource(args);
			case "import-evidence" -> {
				boolean published = args.containsOption("publish");
				String digest = evidence.importFile(Path.of(arg(args, "file")), published);
				System.out.printf("Guide evidence %s: %s%n", digest, published ? "published" : "draft");
			}
			default -> throw new IllegalArgumentException("Unknown guide command");
		}
	}

	private void importIndex() {
		String body = http.getBounded(INDEX, 30, 32 * 1024 * 1024, "guide_index_unavailable");
		JsonNode index = http.parse(body, "guide_index_unavailable");
		if (!index.isObject() || index.isEmpty()) {
			throw new IllegalArgumentException("Expected a nonempty guide index");
		}
		String digest = hash(body);
		var entries = new ArrayList<Entry>();
		for (var item : index.properties()) {
			Entry entry = parse(item.getKey(), item.getValue());
			if (entry != null) {
				entries.add(entry);
			}
		}
		if (entries.isEmpty()) {
			throw new IllegalArgumentException("Guide index contains no usable entries");
		}
		leases.execute("guide-index", 180, 5, operation -> {
			transactions.executeWithoutResult(status -> {
				leases.fence("guide-index", operation);
				jdbc.sql("""
						INSERT INTO guide_index_versions(digest, source_url, retrieved_at)
						VALUES (:digest, :url, now()) ON CONFLICT DO NOTHING
						""").param("digest", digest).param("url", INDEX).update();
				jdbc.sql("UPDATE indexed_guides SET active = false WHERE active").update();
				for (int offset = 0; offset < entries.size(); offset += 250) {
					saveIndexBatch(digest, entries.subList(offset, Math.min(offset + 250, entries.size())));
				}
				leases.release("guide-index", operation, 0);
			});
			return null;
		});
		System.out.printf("Imported %d guides. SHA-256 %s%n", entries.size(), digest);
	}

	private Entry parse(String key, JsonNode row) {
		if (!row.isObject() || !row.path("a").isIntegralNumber() || !row.path("a").canConvertToInt()
				|| row.path("a").asInt() < 0) {
			throw new IllegalArgumentException("Invalid guide attributes");
		}
		int flags = row.path("a").asInt();
		var sources = SOURCES.entrySet().stream().filter(source -> (flags & source.getKey()) != 0).toList();
		if ((flags & 32) == 0 || (flags & 64) != 0 || sources.size() != 1) {
			return null;
		}
		Source source = sources.getFirst().getValue();
		String title = row.path("n").asText("");
		String path = source.id().equals("psnprofiles") ? key : row.path("p").asText(key);
		if (path.isEmpty()) {
			path = key;
		}
		if (title.isBlank() || title.length() > 500 || !row.path("g").isArray() || !row.path("u").isArray()
				|| !row.path("r").isArray()) {
			throw new IllegalArgumentException("Invalid guide entry");
		}
		if (path.isEmpty() || path.length() > 250 || path.startsWith("/") || path.contains("..")
				|| path.matches(".*[:\\\\?#].*") || (source.id().equals("psnprofiles") && !path.matches("[0-9]+"))) {
			return null;
		}
		int count = 0;
		for (var grade : row.path("t")) {
			if (!grade.isIntegralNumber() || !grade.canConvertToInt() || grade.asInt() < 0) {
				count = 0;
				break;
			}
			count = Math.addExact(count, grade.asInt());
		}
		var ids = json.createArrayNode();
		for (var id : row.path("g")) {
			if (id.isIntegralNumber() && id.asInt() > 0) {
				ids.add(id.asInt());
			}
		}
		var ratings = row.path("r");
		return new Entry(source.id() + ":" + key, hash(canonical(row)), source.id(), source.name(), title,
				GuideDiscovery.normalize(title), source.root() + encodePath(path), flags, ids, row.path("u"),
				rating(ratings.path(0), 10), rating(ratings.path(1), Double.MAX_VALUE),
				rating(ratings.path(2), Double.MAX_VALUE), count > 0 ? count : null);
	}

	private static String encodePath(String path) {
		StringBuilder encoded = new StringBuilder();
		for (byte item : path.getBytes(StandardCharsets.UTF_8)) {
			int c = Byte.toUnsignedInt(item);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "/-_.~".indexOf(c) >= 0) {
				encoded.append((char) c);
			}
			else {
				encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits(item));
			}
		}
		return encoded.toString();
	}

	private String canonical(JsonNode value) {
		if (value.isObject()) {
			var fields = new TreeMap<String, JsonNode>();
			value.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
			return fields.entrySet()
				.stream()
				.map(field -> quote(field.getKey()) + ":" + canonical(field.getValue()))
				.collect(java.util.stream.Collectors.joining(",", "{", "}"));
		}
		if (value.isArray()) {
			var elements = new ArrayList<String>();
			value.forEach(element -> elements.add(canonical(element)));
			return "[" + String.join(",", elements) + "]";
		}
		return value.isString() ? quote(value.asText()) : value.toString();
	}

	private String quote(String value) {
		var encoded = json.writeValueAsString(value);
		var escaped = new StringBuilder();
		for (char character : encoded.toCharArray()) {
			escaped.append(character >= 127 ? String.format("\\u%04x", (int) character) : String.valueOf(character));
		}
		return escaped.toString();
	}

	private static Double rating(JsonNode value, double max) {
		double number = value.asDouble(0);
		return number > 0 && number <= max ? number : null;
	}

	private void saveIndexBatch(String digest, List<Entry> entries) {
		jdbc.sql("""
				INSERT INTO indexed_guides(entry_id, version_digest, content_digest, active, source,
				  source_name, title, game_name, url, attributes, external_game_ids, authors,
				  difficulty, playthroughs, platinum_hours, trophy_count)
				SELECT id, :version, digest, true, source, "sourceName", title, "gameName", url, attributes,
				  "gameIds", authors, difficulty, playthroughs, hours, "trophyCount"
				FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS i(
				  id text, digest text, source text, "sourceName" text, title text, "gameName" text,
				  url text, attributes int, "gameIds" jsonb, authors jsonb, difficulty float8,
				  playthroughs float8, hours float8, "trophyCount" int)
				ON CONFLICT(entry_id) DO UPDATE SET version_digest = excluded.version_digest,
				  content_digest = excluded.content_digest, active = true, source = excluded.source,
				  source_name = excluded.source_name, title = excluded.title, game_name = excluded.game_name,
				  url = excluded.url, attributes = excluded.attributes, external_game_ids = excluded.external_game_ids,
				  authors = excluded.authors, difficulty = excluded.difficulty, playthroughs = excluded.playthroughs,
				  platinum_hours = excluded.platinum_hours, trophy_count = excluded.trophy_count
				""").param("version", digest).param("rows", json.writeValueAsString(entries)).update();
	}

	private void list(String title) {
		jdbc.sql("""
				SELECT entry_id || ' | ' || title || ' | ' || source_name || ' | ' || url
				FROM indexed_guides WHERE active AND game_name = :title ORDER BY entry_id
				""")
			.param("title", GuideDiscovery.normalize(title))
			.query(String.class)
			.list()
			.forEach(System.out::println);
	}

	private void bind(ApplicationArguments args) {
		String service = service(args);
		String set = set(args);
		String entry = arg(args, "entry-id");
		String rationale = arg(args, "rationale").strip();
		if (rationale.length() < 30) {
			throw new IllegalArgumentException("Guide review requires a written rationale");
		}
		transactions.executeWithoutResult(status -> {
			var candidates = jdbc.sql("""
					SELECT DISTINCT title, platform, total FROM library_games WHERE trophy_set_id = :set
					AND coalesce(trophy_service, CASE WHEN 'PS5' = ANY(string_to_array(platform, ','))
					      THEN 'trophy2' ELSE 'trophy' END) = :service
					""")
				.param("set", set)
				.param("service", service)
				.query((row, index) -> new GameIdentity(row.getString(1), row.getString(2),
						row.getObject(3, Integer.class)))
				.list();
			if (candidates.size() != 1) {
				throw new IllegalArgumentException("A unique saved game identity is required");
			}
			var game = candidates.getFirst();
			jdbc.sql("INSERT INTO trophy_sets(service, set_id) VALUES (:service, :set) ON CONFLICT DO NOTHING")
				.param("service", service)
				.param("set", set)
				.update();
			int rows = jdbc
				.sql("""
						INSERT INTO guide_bindings(service, set_id, entry_id, version_digest, content_digest,
						    game_title, platform, reviewed_at, rationale, match_method)
						SELECT :service, :set, entry_id, version_digest, content_digest, :title, :platform, now(),
						    :rationale, 'manual' FROM indexed_guides
						WHERE entry_id = :entry AND active AND game_name = :normalized AND trophy_count = :total
						    AND (attributes & :flag) <> 0
						ON CONFLICT(service, set_id) DO UPDATE SET entry_id = excluded.entry_id,
						    version_digest = excluded.version_digest, content_digest = excluded.content_digest,
						    game_title = excluded.game_title, platform = excluded.platform, reviewed_at = excluded.reviewed_at,
						    rationale = excluded.rationale, match_method = excluded.match_method
						""")
				.param("service", service)
				.param("set", set)
				.param("entry", entry)
				.param("title", game.title())
				.param("platform", game.platform())
				.param("normalized", GuideDiscovery.normalize(game.title()))
				.param("total", game.total())
				.param("flag", FLAGS.getOrDefault(game.platform(), 0))
				.param("rationale", rationale)
				.update();
			if (rows != 1) {
				throw new IllegalArgumentException(
						"The guide does not match the saved edition, platform and trophy count");
			}
		});
		System.out.println("Reviewed guide bound to the exact stored trophy set.");
	}

	private record GameIdentity(String title, String platform, Integer total) {
	}

	private void resource(ApplicationArguments args) {
		String service = service(args);
		String set = set(args);
		int trophy = Integer.parseInt(arg(args, "trophy-id"));
		String title = arg(args, "title");
		String source = arg(args, "source-name");
		String url = arg(args, "url");
		String kind = arg(args, "kind");
		String language = arg(args, "language");
		URI uri = URI.create(url);
		if (trophy < 0 || title.length() > 180 || source.length() > 100 || url.length() > 2000
				|| !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
				|| !kind.matches("video|article") || language.length() > 16
				|| !language.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
			throw new IllegalArgumentException("Invalid curated resource");
		}
		String id = hash(String.join(":", service, set, String.valueOf(trophy), language, url));
		boolean published = args.containsOption("publish");
		jdbc.sql(
				"""
						INSERT INTO trophy_resources(resource_id, service, set_id, trophy_id, title, source_name,
						    source_url, kind, language, contains_spoilers, published, reviewed_at)
						VALUES (:id, :service, :set, :trophy, :title, :source, :url, :kind, :language, :spoilers, :published, now())
						ON CONFLICT(resource_id) DO UPDATE SET title = excluded.title, source_name = excluded.source_name,
						    kind = excluded.kind, contains_spoilers = excluded.contains_spoilers,
						    published = excluded.published, reviewed_at = excluded.reviewed_at
						""")
			.param("id", id)
			.param("service", service)
			.param("set", set)
			.param("trophy", trophy)
			.param("title", title)
			.param("source", source)
			.param("url", url)
			.param("kind", kind)
			.param("language", language)
			.param("spoilers", !args.containsOption("spoiler-safe"))
			.param("published", published)
			.update();
		System.out.printf("Curated resource %s: %s%n", id, published ? "published" : "draft");
	}

	private static String service(ApplicationArguments args) {
		String value = arg(args, "service");
		if (!value.matches("trophy|trophy2")) {
			throw new IllegalArgumentException("Invalid trophy service");
		}
		return value;
	}

	private static String set(ApplicationArguments args) {
		String value = arg(args, "set-id");
		if (!value.matches("NPWR[0-9]+_[0-9]{2}")) {
			throw new IllegalArgumentException("Invalid trophy set");
		}
		return value;
	}

	private static String arg(ApplicationArguments args, String name) {
		var values = args.getOptionValues(name);
		if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException("Expected one value for --" + name);
		}
		return values.getFirst();
	}

	private static String hash(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
