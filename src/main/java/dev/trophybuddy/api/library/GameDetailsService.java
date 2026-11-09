package dev.trophybuddy.api.library;

import dev.trophybuddy.api.catalog.CatalogService;
import dev.trophybuddy.api.psn.PsnClient;
import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.OperationLeases;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class GameDetailsService {

	private final LibraryRepository libraries;

	private final DetailStore details;

	private final PsnClient psn;

	private final CatalogService catalog;

	private final OperationLeases leases;

	private final ObjectMapper json;

	GameDetailsService(LibraryRepository libraries, DetailStore details, PsnClient psn, CatalogService catalog,
			OperationLeases leases, ObjectMapper json) {
		this.libraries = libraries;
		this.details = details;
		this.psn = psn;
		this.catalog = catalog;
		this.leases = leases;
		this.json = json;
	}

	ObjectNode load(String uid, String id, boolean refresh) {
		if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
			throw new ApiFailure("game_not_found", 404);
		}
		var library = libraries.find(uid, LibraryRepository.Order.last_played_at, LibraryRepository.Direction.desc);
		if (library == null) {
			throw new ApiFailure("game_not_found", 404);
		}
		var game = library.games()
			.stream()
			.filter(entry -> entry.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new ApiFailure("game_not_found", 404));
		if (!game.trophySetId().matches("NPWR[0-9]+_[0-9]{2}")) {
			throw new ApiFailure("game_not_found", 404);
		}
		var previous = details.find(uid, id);
		boolean sameProfile = previous != null
				&& library.profile().accountId().equals(previous.path("profile").path("accountId").asText());
		if (!refresh && sameProfile && library.syncedAt().equals(previous.path("librarySyncedAt").asText())) {
			return details.cached(uid, library, game, previous);
		}
		try {
			return leases.execute("details:" + uid + ":" + id, 300, 15, operation -> {
				var trophies = psn.trophies(game, library.profile().accountId());
				if (sameProfile && hasKnownEarnings(previous)
						&& trophies.trophies().stream().anyMatch(trophy -> trophy.earned() == null)) {
					throw new ApiFailure("incomplete_trophy_earnings");
				}
				var snapshot = json.createObjectNode()
					.put("version", 1)
					.put("librarySyncedAt", library.syncedAt())
					.put("fetchedAt", PsnClient.timestamp())
					.put("trophyStatus", "available");
				snapshot.set("profile", json.valueToTree(library.profile()));
				snapshot.set("game", json.valueToTree(game));
				snapshot.set("catalog", catalog.find(game.title(), game.platform()));
				return details.publish(uid, library, game, operation, snapshot, trophies);
			});
		}
		catch (DataAccessException failure) {
			throw new ApiFailure("game_details_failed");
		}
	}

	private static boolean hasKnownEarnings(ObjectNode details) {
		for (var trophy : details.path("trophies")) {
			if (trophy.path("earned").isBoolean()) {
				return true;
			}
		}
		return false;
	}

}
