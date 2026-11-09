package dev.trophybuddy.api.library;

import dev.trophybuddy.api.support.OperationLeases;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
class LibraryWriter {

	private final JdbcClient jdbc;

	private final OperationLeases leases;

	private final ObjectMapper json;

	LibraryWriter(JdbcClient jdbc, OperationLeases leases, ObjectMapper json) {
		this.jdbc = jdbc;
		this.leases = leases;
		this.json = json;
	}

	/** Publish a full generation and keep detail observations for retained game IDs. */
	@Transactional
	void publish(String uid, LibrarySnapshot snapshot, String operation) {
		String resource = "library:" + uid;
		leases.fence(resource, operation);
		var profile = snapshot.profile();
		var summary = profile.trophySummary();
		String profileSummary = null;
		if (summary != null) {
			profileSummary = json.writeValueAsString(java.util.Map.of("level", summary.level(), "progress",
					summary.progress(), "earned_trophies", summary.earnedTrophies()));
		}
		jdbc.sql("INSERT INTO users(uid) VALUES (:uid) ON CONFLICT DO NOTHING").param("uid", uid).update();
		jdbc.sql("""
				INSERT INTO psn_profiles(account_id, online_id, avatar_url, trophy_summary)
				VALUES (:account, :onlineId, :avatar, CAST(:summary AS jsonb))
				ON CONFLICT(account_id) DO UPDATE SET online_id = excluded.online_id,
				  avatar_url = excluded.avatar_url, trophy_summary = excluded.trophy_summary
				""")
			.param("account", profile.accountId())
			.param("onlineId", profile.onlineId())
			.param("avatar", profile.avatarUrl())
			.param("summary", profileSummary)
			.update();
		jdbc.sql("""
				INSERT INTO libraries(uid, account_id, synced_at) VALUES (:uid, :account, :syncedAt)
				ON CONFLICT(uid) DO UPDATE SET account_id = excluded.account_id, synced_at = excluded.synced_at
				""")
			.param("uid", uid)
			.param("account", profile.accountId())
			.param("syncedAt", snapshot.syncedAt())
			.update();

		String games = json.writeValueAsString(snapshot.games());
		jdbc.sql(
				"""
						INSERT INTO library_games(uid, game_id, title, platform, image_url, title_ids, trophy_set_id,
						  trophy_service, earned, total, progress, platinum, last_played_at, trophy_progress_updated_at)
						SELECT :uid, id, title, platform, "imageUrl", "titleIds", "trophySetId", "trophyService",
						  earned, total, progress, platinum, "lastPlayedAt", "trophyProgressUpdatedAt"
						FROM jsonb_to_recordset(CAST(:games AS jsonb)) AS g(
						  id text, title text, platform text, "imageUrl" text, "titleIds" jsonb, "trophySetId" text,
						  "trophyService" text, earned int, total int, progress int, platinum boolean,
						  "lastPlayedAt" timestamptz, "trophyProgressUpdatedAt" timestamptz)
						ON CONFLICT(uid, game_id) DO UPDATE SET title = excluded.title, platform = excluded.platform,
						  image_url = excluded.image_url, title_ids = excluded.title_ids, trophy_set_id = excluded.trophy_set_id,
						  trophy_service = excluded.trophy_service, earned = excluded.earned, total = excluded.total,
						  progress = excluded.progress, platinum = excluded.platinum, last_played_at = excluded.last_played_at,
						  trophy_progress_updated_at = excluded.trophy_progress_updated_at
						""")
			.param("uid", uid)
			.param("games", games)
			.update();
		jdbc.sql("""
				DELETE FROM library_games WHERE uid = :uid AND game_id NOT IN (
				  SELECT item->>'id' FROM jsonb_array_elements(CAST(:games AS jsonb)) AS item
				)
				""").param("uid", uid).param("games", games).update();
		leases.release(resource, operation, 60);
	}

}
