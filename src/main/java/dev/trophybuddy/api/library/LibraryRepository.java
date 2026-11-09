package dev.trophybuddy.api.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class LibraryRepository {

	enum Order {

		last_played_at("g.last_played_at"), trophy_progress_updated_at("g.trophy_progress_updated_at"),
		title("g.title");

		final String column;

		Order(String column) {
			this.column = column;
		}

	}

	enum Direction {

		asc, desc

	}

	private final JdbcClient jdbc;

	LibraryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Read the profile and game rows from one committed PostgreSQL snapshot. */
	LibrarySnapshot find(String uid, Order order, Direction direction) {
		String sql = """
				SELECT l.synced_at, p.account_id, p.online_id, p.avatar_url,
				       p.trophy_summary IS NOT NULL AS has_summary,
				       (p.trophy_summary->>'level')::int AS trophy_level,
				       (p.trophy_summary->>'progress')::int AS level_progress,
				       (p.trophy_summary->'earned_trophies'->>'bronze')::int AS bronze,
				       (p.trophy_summary->'earned_trophies'->>'silver')::int AS silver,
				       (p.trophy_summary->'earned_trophies'->>'gold')::int AS gold,
				       (p.trophy_summary->'earned_trophies'->>'platinum')::int AS platinum_count,
				       g.*, ARRAY(SELECT jsonb_array_elements_text(g.title_ids)) AS title_id_array
				FROM libraries l
				JOIN psn_profiles p ON p.account_id = l.account_id
				LEFT JOIN library_games g ON g.uid = l.uid AND g.trophy_set_id <> ''
				WHERE l.uid = :uid
				ORDER BY %s %s NULLS LAST, g.game_id ASC
				""".formatted(order.column, direction.name());

		return jdbc.sql(sql).param("uid", uid).query(this::extract).orElse(null);
	}

	private Optional<LibrarySnapshot> extract(ResultSet rows) throws SQLException {
		if (!rows.next()) {
			return Optional.empty();
		}

		var summary = rows.getBoolean("has_summary") ? new LibrarySnapshot.Summary(rows.getInt("trophy_level"),
				rows.getInt("level_progress"), new LibrarySnapshot.Counts(rows.getInt("bronze"), rows.getInt("silver"),
						rows.getInt("gold"), rows.getInt("platinum_count")))
				: null;
		var profile = new LibrarySnapshot.Profile(rows.getString("account_id"), rows.getString("online_id"),
				rows.getString("avatar_url"), summary);
		String syncedAt = rows.getString("synced_at");
		var games = new ArrayList<LibrarySnapshot.Game>();

		do {
			if (rows.getString("game_id") != null) {
				games.add(game(rows));
			}
		}
		while (rows.next());

		return Optional.of(new LibrarySnapshot(1, profile, syncedAt, List.copyOf(games)));
	}

	private LibrarySnapshot.Game game(ResultSet row) throws SQLException {
		var playedAt = row.getObject("last_played_at", OffsetDateTime.class);
		var trophyAt = row.getObject("trophy_progress_updated_at", OffsetDateTime.class);
		var array = row.getArray("title_id_array");
		List<String> titleIds;
		try {
			titleIds = List.copyOf(Arrays.asList((String[]) array.getArray()));
		}
		finally {
			array.free();
		}

		return new LibrarySnapshot.Game(row.getString("game_id"), row.getString("title"), row.getString("platform"),
				row.getString("image_url"), titleIds, row.getString("trophy_set_id"), row.getString("trophy_service"),
				row.getObject("earned", Integer.class), row.getObject("total", Integer.class),
				row.getObject("progress", Integer.class), row.getBoolean("platinum"),
				playedAt == null ? null : playedAt.toInstant(), trophyAt == null ? null : trophyAt.toInstant());
	}

}
