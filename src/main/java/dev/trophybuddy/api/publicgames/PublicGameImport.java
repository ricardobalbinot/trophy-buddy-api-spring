package dev.trophybuddy.api.publicgames;

import dev.trophybuddy.api.catalog.CatalogService;
import dev.trophybuddy.api.library.TrophyDefinitionStore;
import dev.trophybuddy.api.psn.PsnClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty("trophybuddy.public-games-file")
class PublicGameImport implements ApplicationRunner {

	private static final Set<String> REQUIREMENTS = Set.of("confirmed_none", "confirmed_some", "unknown");

	private final PsnClient psn;

	private final CatalogService catalog;

	private final TrophyDefinitionStore definitions;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final ObjectMapper json;

	PublicGameImport(PsnClient psn, CatalogService catalog, TrophyDefinitionStore definitions, JdbcClient jdbc,
			TransactionTemplate transactions, ObjectMapper json) {
		this.psn = psn;
		this.catalog = catalog;
		this.definitions = definitions;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.json = json;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		var values = args.getOptionValues("trophybuddy.public-games-file");
		if (values == null || values.size() != 1) {
			throw new IllegalArgumentException("Provide one public game manifest");
		}
		try (var input = Files.newInputStream(Path.of(values.getFirst()))) {
			var entries = json.readValue(input, Edition[].class);
			Arrays.stream(entries).forEach(PublicGameImport::validate);
			for (var entry : entries) {
				importEdition(entry);
				System.out.printf("Imported public edition %s%n", entry.slug());
			}
		}
	}

	/** Fetch outside the transaction and publish each complete edition atomically. */
	void importEdition(Edition entry) {
		String observedAt = PsnClient.timestamp();
		var observations = psn.trophyDefinitions(entry.service(), entry.setId());
		if (observations.trophies().isEmpty()) {
			throw new IllegalStateException("PSN returned no trophy definitions for " + entry.slug());
		}
		validateTrophyReferences(entry, observations);
		var metadata = catalog.find(entry.title(), entry.platform());
		String cover = metadata == null ? null : metadata.path("coverUrl").asText(null);
		transactions.executeWithoutResult(status -> {
			jdbc.sql("INSERT INTO trophy_sets(service, set_id) VALUES (:service, :set) ON CONFLICT DO NOTHING")
				.param("service", entry.service())
				.param("set", entry.setId())
				.update();
			definitions.save(entry.service(), entry.setId(), observedAt,
					json.writeValueAsString(observations.trophies()), json.writeValueAsString(observations.groups()));
			int saved = jdbc
				.sql("""
						INSERT INTO public_game_editions(slug, service, set_id, title, platform, cover_url)
						VALUES (:slug, :service, :set, :title, :platform, :cover)
						ON CONFLICT (slug) DO UPDATE SET title = excluded.title, platform = excluded.platform,
						    cover_url = coalesce(excluded.cover_url, public_game_editions.cover_url)
						WHERE (public_game_editions.service, public_game_editions.set_id) = (excluded.service, excluded.set_id)
						""")
				.param("slug", entry.slug())
				.param("service", entry.service())
				.param("set", entry.setId())
				.param("title", entry.title())
				.param("platform", entry.platform())
				.param("cover", cover)
				.update();
			if (saved != 1) {
				throw new IllegalArgumentException("A public slug cannot change trophy sets");
			}
			if (entry.guide() != null) {
				saveGuide(entry);
			}
		});
	}

	/** Resolve checkpoint references against the exact edition being published. */
	private static void validateTrophyReferences(Edition entry, PsnClient.Observations observations) {
		if (entry.guide() == null) {
			return;
		}
		var trophies = observations.trophies()
			.stream()
			.collect(Collectors.toMap(PsnClient.Trophy::id, Function.identity()));
		for (var checkpoint : entry.guide().checkpoints()) {
			if (checkpoint.trophyIds() == null) {
				continue;
			}
			var ids = new HashSet<Integer>();
			for (var id : checkpoint.trophyIds()) {
				var trophy = trophies.get(id);
				if (trophy == null || !ids.add(id) || (trophy.hidden() && checkpoint.spoiler() == null)) {
					throw new IllegalArgumentException(
							"Invalid trophy reference for " + entry.slug() + "/" + checkpoint.id());
				}
			}
		}
	}

	private void saveGuide(Edition entry) {
		int saved = jdbc.sql("""
				INSERT INTO public_guide_revisions(slug, revision, content)
				VALUES (:slug, :revision, CAST(:content AS jsonb))
				ON CONFLICT (slug, revision) DO UPDATE SET content = excluded.content
				WHERE public_guide_revisions.content = excluded.content
				""")
			.param("slug", entry.slug())
			.param("revision", entry.guide().revision())
			.param("content", json.writeValueAsString(entry.guide()))
			.update();
		if (saved != 1) {
			throw new IllegalArgumentException("Increment the revision when editing a published guide");
		}
	}

	private static void validate(Edition edition) {
		if (edition == null || !matches(edition.slug(), "[a-z0-9]+(?:-[a-z0-9]+)*") || edition.slug().length() > 120
				|| !matches(edition.service(), "trophy|trophy2")
				|| !matches(edition.setId(), "NPWR[0-9]{1,12}_[0-9]{2}")
				|| !matches(edition.platform(), "PS3|PS4|PS5|PSVITA") || edition.title() == null
				|| edition.title().isBlank()) {
			throw new IllegalArgumentException("Invalid public edition");
		}
		var guide = edition.guide();
		if (guide == null) {
			return;
		}
		if (guide.revision() < 1 || guide.missables() == null || !REQUIREMENTS.contains(guide.missables())
				|| guide.online() == null || !REQUIREMENTS.contains(guide.online()) || guide.preparation() == null
				|| guide.checkpoints() == null
				|| (guide.firstPlaythroughNote() != null && guide.firstPlaythroughNote().isBlank())
				|| (guide.checkpoints().isEmpty() && !"confirmed_none".equals(guide.missables()))
				|| guide.sources() == null || guide.sources().isEmpty()) {
			throw new IllegalArgumentException("Invalid public guide for " + edition.slug());
		}
		var sources = new HashSet<String>();
		for (var source : guide.sources()) {
			if (source == null || !matches(source.id(), "[a-z0-9-]+") || !sources.add(source.id())
					|| source.name() == null || source.name().isBlank() || source.url() == null
					|| !source.url().startsWith("https://") || java.net.URI.create(source.url()).getHost() == null
					|| java.net.URI.create(source.url()).getUserInfo() != null) {
				throw new IllegalArgumentException("Invalid source for " + edition.slug());
			}
		}
		var checkpoints = new HashSet<String>();
		for (var item : guide.checkpoints()) {
			if (item == null || !matches(item.id(), "[a-z0-9-]+") || !checkpoints.add(item.id()) || item.title() == null
					|| item.title().isBlank() || item.action() == null || item.action().isBlank()
					|| (item.spoiler() != null && item.spoiler().isBlank()) || item.sourceIds() == null
					|| item.sourceIds().isEmpty() || !sources.containsAll(item.sourceIds())) {
				throw new IllegalArgumentException("Invalid checkpoint for " + edition.slug());
			}
		}
		if (guide.routes() != null) {
			var routes = new HashSet<String>();
			for (var route : guide.routes()) {
				if (route == null || !matches(route.id(), "[a-z0-9-]+") || !routes.add(route.id())
						|| route.title() == null || route.title().isBlank() || route.playthroughs() < 1
						|| route.description() == null || route.description().isBlank() || route.checkpointIds() == null
						|| route.checkpointIds().isEmpty() || !checkpoints.containsAll(route.checkpointIds())
						|| new HashSet<>(route.checkpointIds()).size() != route.checkpointIds().size()) {
					throw new IllegalArgumentException("Invalid route for " + edition.slug());
				}
			}
		}
	}

	private static boolean matches(String value, String pattern) {
		return value != null && value.matches(pattern);
	}

	record Edition(String slug, String service, String setId, String title, String platform, PublicGuide guide) {
	}

}
