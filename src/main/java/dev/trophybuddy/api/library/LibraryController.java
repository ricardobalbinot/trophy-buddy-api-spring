package dev.trophybuddy.api.library;

import dev.trophybuddy.api.support.ApiFailure;
import java.security.Principal;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/library")
class LibraryController {

	private final LibraryRepository repository;

	private final LibrarySync sync;

	private final GameDetailsService details;

	LibraryController(LibraryRepository repository, LibrarySync sync, GameDetailsService details) {
		this.sync = sync;
		this.details = details;
		this.repository = repository;
	}

	@GetMapping
	LibraryResponse library(Principal principal,
			@RequestParam(defaultValue = "last_played_at") LibraryRepository.Order sort,
			@RequestParam(defaultValue = "desc") LibraryRepository.Direction direction) {
		return new LibraryResponse(repository.find(principal.getName(), sort, direction));
	}

	@GetMapping("/statistics")
	Statistics statistics(Principal principal) {
		var snapshot = repository.find(principal.getName(), LibraryRepository.Order.last_played_at,
				LibraryRepository.Direction.desc);

		return Statistics.from(snapshot == null ? List.of() : snapshot.games());
	}

	@PostMapping("/sync")
	LibraryResponse synchronize(Principal principal, @RequestBody JsonNode body) {
		String onlineId = body.path("onlineId").asText("");
		if (!body.isObject() || body.size() != 1 || !body.path("onlineId").isString()
				|| !onlineId.matches("[a-zA-Z][a-zA-Z0-9_-]{2,15}")) {
			throw new ApiFailure("invalid_request", 422);
		}
		return new LibraryResponse(sync.synchronize(principal.getName(), onlineId));
	}

	@PostMapping("/games/{game_id}")
	Map<String, JsonNode> game(Principal principal, @PathVariable("game_id") String gameId,
			@RequestBody JsonNode body) {
		if (!body.isObject() || (body.size() > 0 && (body.size() != 1 || !body.path("refresh").isBoolean()))) {
			throw new ApiFailure("invalid_request", 422);
		}
		return Map.of("details", details.load(principal.getName(), gameId, body.path("refresh").asBoolean(false)));
	}

	record LibraryResponse(LibrarySnapshot snapshot) {
	}

	record Statistics(long totalGames, long gamesWithEarnedTrophies, long gamesWithoutEarnedTrophies,
			long gamesWithUnknownProgress, long platinumGames, long knownEarnedTrophies) {
		static Statistics from(List<LibrarySnapshot.Game> games) {
			long positive = 0;
			long zero = 0;
			long unknown = 0;
			long platinum = 0;
			long earned = 0;
			for (var game : games) {
				if (game.earned() == null) {
					unknown++;
				}
				else if (game.earned() == 0) {
					zero++;
				}
				else {
					positive++;
					earned += game.earned();
				}
				if (game.platinum()) {
					platinum++;
				}
			}

			return new Statistics(games.size(), positive, zero, unknown, platinum, earned);
		}
	}

}
