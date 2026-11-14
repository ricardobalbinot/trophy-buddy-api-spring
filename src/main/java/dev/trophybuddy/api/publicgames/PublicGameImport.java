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

	private static final String MANIFEST_OPTION = "trophybuddy.public-games-file";

	private static final String SLUG_OPTION = "trophybuddy.public-games-slug";

	private static final String RATE_PROFILE_OPTION = "trophybuddy.public-games-rate-profile";

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
		String manifest = singleOption(args, MANIFEST_OPTION);
		if (manifest == null) {
			throw new IllegalArgumentException("Provide one public game manifest");
		}
		String slug = singleOption(args, SLUG_OPTION);
		String rateProfile = singleOption(args, RATE_PROFILE_OPTION);
		if (rateProfile != null && !matches(rateProfile, "[A-Za-z][A-Za-z0-9_-]{2,15}")) {
			throw new IllegalArgumentException("Provide one public PSN Online ID for rate lookup");
		}
		try (var input = Files.newInputStream(Path.of(manifest))) {
			var entries = json.readValue(input, Edition[].class);
			Arrays.stream(entries).forEach(PublicGameImport::validate);
			var selected = Arrays.stream(entries).filter(entry -> slug == null || slug.equals(entry.slug())).toList();
			if (selected.isEmpty()) {
				throw new IllegalArgumentException("No matching public edition in the manifest");
			}
			String rateAccount = rateProfile == null ? null : psn.resolvePublicAccount(rateProfile);
			for (var entry : selected) {
				importEdition(entry, rateAccount);
				reportRateCoverage(entry);
			}
		}
	}

	private static String singleOption(ApplicationArguments args, String name) {
		if (!args.containsOption(name)) {
			return null;
		}
		var values = args.getOptionValues(name);
		if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException("Provide one value for " + name);
		}
		return values.getFirst();
	}

	/**
	 * Report stored rates so a partial response does not hide earlier successful imports.
	 */
	private void reportRateCoverage(Edition entry) {
		var coverage = jdbc.sql("""
				SELECT count(global_earned_rate), count(*) FROM trophies WHERE service = :service AND set_id = :set
				""")
			.param("service", entry.service())
			.param("set", entry.setId())
			.query((row, index) -> "%d/%d".formatted(row.getLong(1), row.getLong(2)))
			.single();
		System.out.printf("Imported public edition %s (%s global rates stored)%n", entry.slug(), coverage);
	}

	/** Fetch outside the transaction and publish each complete edition atomically. */
	private void importEdition(Edition entry, String rateAccount) {
		var observations = psn.trophyDefinitions(entry.service(), entry.setId(), rateAccount);
		String observedAt = PsnClient.timestamp();
		if (observations.trophies().isEmpty()) {
			throw new IllegalStateException("PSN returned no trophy definitions for " + entry.slug());
		}
		validateTrophyReferences(entry, observations);
		var metadata = catalog.find(entry.title(), entry.platform());
		String cover = metadata == null ? null : metadata.path("coverUrl").asText(null);
		var storyHours = metadata == null ? null : metadata.path("mainStoryHours");
		transactions.executeWithoutResult(status -> {
			jdbc.sql("INSERT INTO trophy_sets(service, set_id) VALUES (:service, :set) ON CONFLICT DO NOTHING")
				.param("service", entry.service())
				.param("set", entry.setId())
				.update();
			definitions.save(entry.service(), entry.setId(), observedAt,
					json.writeValueAsString(observations.trophies()), json.writeValueAsString(observations.groups()));
			int saved = jdbc
				.sql("""
						INSERT INTO public_game_editions(slug, service, set_id, title, platform, cover_url, main_story_hours)
						VALUES (:slug, :service, :set, :title, :platform, :cover, :storyHours)
						ON CONFLICT (slug) DO UPDATE SET title = excluded.title, platform = excluded.platform,
						    cover_url = coalesce(excluded.cover_url, public_game_editions.cover_url),
						    main_story_hours = coalesce(excluded.main_story_hours, public_game_editions.main_story_hours)
						WHERE (public_game_editions.service, public_game_editions.set_id) = (excluded.service, excluded.set_id)
						""")
				.param("slug", entry.slug())
				.param("service", entry.service())
				.param("set", entry.setId())
				.param("title", entry.title())
				.param("platform", entry.platform())
				.param("cover", cover)
				.param("storyHours", storyHours != null && storyHours.isNumber() ? storyHours.asDouble() : null)
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
		if (entry.guide().walkthroughs() != null) {
			var assigned = new HashSet<Integer>();
			for (var walkthrough : entry.guide().walkthroughs()) {
				for (var id : walkthrough.trophyIds()) {
					var trophy = trophies.get(id);
					if (trophy == null || trophy.hidden() || !assigned.add(id)) {
						throw new IllegalArgumentException("Invalid walkthrough trophy for " + entry.slug());
					}
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
		if (guide.walkthroughs() != null) {
			for (var walkthrough : guide.walkthroughs()) {
				if (walkthrough == null || walkthrough.trophyIds() == null || walkthrough.trophyIds().isEmpty()
						|| walkthrough.steps() == null
						|| walkthrough.steps().stream().anyMatch(step -> step == null || step.isBlank())
						|| (walkthrough.steps().isEmpty() && walkthrough.videoId() == null)
						|| (walkthrough.videoId() != null && !matches(walkthrough.videoId(), "[a-zA-Z0-9_-]{11}"))) {
					throw new IllegalArgumentException("Invalid walkthrough for " + edition.slug());
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
