package dev.trophybuddy.api.library;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrophyDefinitionStore {

	private final JdbcClient jdbc;

	TrophyDefinitionStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void save(String service, String set, String date, String trophies, String groups) {
		jdbc.sql("""
				WITH known AS (
				    SELECT id, name, "imageUrl" FROM jsonb_to_recordset(CAST(:groups AS jsonb))
				    AS g(id text, name text, "imageUrl" text)
				),
				observed AS (SELECT DISTINCT "groupId" AS id
				    FROM jsonb_to_recordset(CAST(:trophies AS jsonb)) AS t("groupId" text))
				INSERT INTO trophy_groups(service, set_id, group_id, name, image_url, observed_at)
				SELECT :service, :set, id, known.name, known."imageUrl", CAST(:date AS timestamptz)
				FROM known FULL JOIN observed USING(id) ORDER BY id
				ON CONFLICT(service, set_id, group_id) DO UPDATE SET name = excluded.name,
				    image_url = coalesce(excluded.image_url, trophy_groups.image_url),
				    observed_at = excluded.observed_at
				WHERE trophy_groups.observed_at <= excluded.observed_at AND excluded.name IS NOT NULL
				""")
			.param("service", service)
			.param("set", set)
			.param("date", date)
			.param("groups", groups)
			.param("trophies", trophies)
			.update();
		jdbc.sql("""
				INSERT INTO trophies(service, set_id, trophy_id, group_id, name, description, image_url,
				    type, hidden, global_earned_rate, observed_at)
				SELECT :service, :set, id, "groupId", name, description, "imageUrl", type, hidden, "earnedRate",
				    CAST(:date AS timestamptz)
				FROM jsonb_to_recordset(CAST(:trophies AS jsonb)) AS t(
				    id int, "groupId" text, name text, description text, "imageUrl" text, type text, hidden boolean,
				    "earnedRate" double precision)
				ORDER BY id
				ON CONFLICT(service, set_id, trophy_id) DO UPDATE SET group_id = excluded.group_id,
				    name = excluded.name, description = excluded.description, image_url = excluded.image_url,
				    type = excluded.type, hidden = excluded.hidden,
				    global_earned_rate = coalesce(excluded.global_earned_rate, trophies.global_earned_rate),
				    observed_at = excluded.observed_at
				WHERE trophies.observed_at <= excluded.observed_at
				""")
			.param("service", service)
			.param("set", set)
			.param("date", date)
			.param("trophies", trophies)
			.update();
	}

}
