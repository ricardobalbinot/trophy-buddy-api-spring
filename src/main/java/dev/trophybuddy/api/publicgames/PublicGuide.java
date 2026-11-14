package dev.trophybuddy.api.publicgames;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

record PublicGuide(int revision, Integer difficulty, Integer hoursMin, Integer hoursMax, Integer playthroughs,
		String missables, String online, List<String> preparation, List<Checkpoint> checkpoints, List<Source> sources,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<Route> routes,
		@JsonInclude(JsonInclude.Include.NON_NULL) String firstPlaythroughNote,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<Walkthrough> walkthroughs) {

	record Walkthrough(List<Integer> trophyIds, List<String> steps, String videoId) {
	}

	record Route(String id, String title, int playthroughs, String description, List<String> checkpointIds) {
	}

	record Checkpoint(String id, String title, String action, boolean critical, String spoiler, List<String> sourceIds,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<Integer> trophyIds) {
	}

	record Source(String id, String name, String url) {
	}

}
