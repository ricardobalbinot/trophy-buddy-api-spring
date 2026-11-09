package dev.trophybuddy.api.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record LibrarySnapshot(int version, Profile profile, String syncedAt, List<Game> games) {
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Profile(String accountId, String onlineId, String avatarUrl, Summary trophySummary) {
	}

	public record Summary(int level, int progress, Counts earnedTrophies) {
	}

	public record Counts(int bronze, int silver, int gold, int platinum) {
	}

	public record Game(String id, String title, String platform, String imageUrl, List<String> titleIds,
			String trophySetId, @JsonInclude(JsonInclude.Include.NON_NULL) String trophyService, Integer earned,
			Integer total, Integer progress, boolean platinum, Instant lastPlayedAt, Instant trophyProgressUpdatedAt) {
	}
}
