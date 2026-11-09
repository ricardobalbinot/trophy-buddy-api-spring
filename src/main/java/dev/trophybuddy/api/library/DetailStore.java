package dev.trophybuddy.api.library;

import dev.trophybuddy.api.guides.GuideDiscovery;
import dev.trophybuddy.api.library.LibrarySnapshot.Game;
import dev.trophybuddy.api.psn.PsnClient;
import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.OperationLeases;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
class DetailStore {

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final OperationLeases leases;

	private final GuideDiscovery guides;

	DetailStore(JdbcClient jdbc, ObjectMapper json, OperationLeases leases, GuideDiscovery guides) {
		this.jdbc = jdbc;
		this.json = json;
		this.leases = leases;
		this.guides = guides;
	}

	ObjectNode find(String uid, String id) {
		return jdbc.sql("""
				SELECT (d.payload || jsonb_build_object('trophies', coalesce((
				  SELECT jsonb_agg(jsonb_build_object(
				    'id', t.trophy_id, 'groupId', t.group_id, 'name', t.name, 'description', t.description,
				    'imageUrl', t.image_url, 'type', t.type, 'hidden', t.hidden, 'earned', p.earned,
				    'earnedAt', p.earned_at, 'earnedRate', p.earned_rate, 'resources', coalesce((
				      SELECT jsonb_agg(jsonb_build_object('id', r.resource_id, 'title', r.title,
				        'sourceName', r.source_name, 'sourceUrl', r.source_url, 'kind', r.kind,
				        'language', r.language, 'containsSpoilers', r.contains_spoilers) ORDER BY r.resource_id)
				      FROM trophy_resources r WHERE (r.service, r.set_id, r.trophy_id) =
				        (t.service, t.set_id, t.trophy_id) AND r.published
				    ), '[]'::jsonb)
				  ) ORDER BY t.trophy_id)
				  FROM profile_trophies p JOIN trophies t USING(service, set_id, trophy_id)
				  WHERE p.uid = d.uid AND p.game_id = d.game_id
				), '[]'::jsonb), 'groups', coalesce((
				  SELECT jsonb_agg(jsonb_build_object('id', g.group_id, 'name', g.name)
				    ORDER BY (g.group_id = 'default') DESC, g.group_id)
				  FROM detail_trophy_groups dg JOIN trophy_groups g USING(service, set_id, group_id)
				  WHERE dg.uid = d.uid AND dg.game_id = d.game_id AND g.name IS NOT NULL
				), '[]'::jsonb)))::text
				FROM game_details d WHERE d.uid = :uid AND d.game_id = :id
				""")
			.param("uid", uid)
			.param("id", id)
			.query(String.class)
			.optional()
			.map(value -> (ObjectNode) json.readTree(value))
			.orElse(null);
	}

	@Transactional
	ObjectNode cached(String uid, LibrarySnapshot snapshot, Game game, ObjectNode details) {
		fenceLibrary(uid, snapshot);
		guides.attach(game, details);
		return details;
	}

	/** Persist shared definitions and private observations as one generation. */
	@Transactional
	ObjectNode publish(String uid, LibrarySnapshot snapshot, Game game, String operation, ObjectNode details,
			PsnClient.Observations observations) {
		String resource = "details:" + uid + ":" + game.id();
		leases.fence(resource, operation);
		fenceLibrary(uid, snapshot);
		jdbc.sql("""
				INSERT INTO game_details(uid, game_id, payload) VALUES (:uid, :id, CAST(:payload AS jsonb))
				ON CONFLICT(uid, game_id) DO UPDATE SET payload = excluded.payload
				""").param("uid", uid).param("id", game.id()).param("payload", details.toString()).update();
		String service = PsnClient.service(game);
		String set = game.trophySetId();
		String date = details.path("fetchedAt").asText();
		String trophies = json.writeValueAsString(observations.trophies());
		String groups = json.writeValueAsString(observations.groups());
		jdbc.sql("DELETE FROM profile_trophies WHERE uid = :uid AND game_id = :id")
			.param("uid", uid)
			.param("id", game.id())
			.update();
		jdbc.sql("DELETE FROM detail_trophy_groups WHERE uid = :uid AND game_id = :id")
			.param("uid", uid)
			.param("id", game.id())
			.update();
		jdbc.sql("INSERT INTO trophy_sets(service, set_id) VALUES (:service, :set) ON CONFLICT DO NOTHING")
			.param("service", service)
			.param("set", set)
			.update();
		saveDefinitions(service, set, date, trophies, groups);
		jdbc.sql("""
				INSERT INTO detail_trophy_groups(uid, game_id, group_id, service, set_id)
				SELECT :uid, :id, g.id, :service, :set
				FROM jsonb_to_recordset(CAST(:groups AS jsonb)) AS g(id text, name text)
				""")
			.param("uid", uid)
			.param("id", game.id())
			.param("service", service)
			.param("set", set)
			.param("groups", groups)
			.update();
		jdbc.sql("""
				INSERT INTO profile_trophies(uid, game_id, account_id, service, set_id, trophy_id,
				    earned, earned_at, earned_rate)
				SELECT :uid, :id, :account, :service, :set, t.id, t.earned, t."earnedAt", t."earnedRate"
				FROM jsonb_to_recordset(CAST(:trophies AS jsonb)) AS t(
				    id int, earned boolean, "earnedAt" text, "earnedRate" double precision)
				""")
			.param("uid", uid)
			.param("id", game.id())
			.param("account", snapshot.profile().accountId())
			.param("service", service)
			.param("set", set)
			.param("trophies", trophies)
			.update();
		leases.release(resource, operation, 60);
		ObjectNode saved = find(uid, game.id());
		guides.attach(game, saved);
		return saved;
	}

	private void saveDefinitions(String service, String set, String date, String trophies, String groups) {
		jdbc.sql(
				"""
						WITH known AS (SELECT id, name FROM jsonb_to_recordset(CAST(:groups AS jsonb)) AS g(id text, name text)),
						observed AS (SELECT DISTINCT "groupId" AS id
						    FROM jsonb_to_recordset(CAST(:trophies AS jsonb)) AS t("groupId" text))
						INSERT INTO trophy_groups(service, set_id, group_id, name, observed_at)
						SELECT :service, :set, id, known.name, CAST(:date AS timestamptz)
						FROM known FULL JOIN observed USING(id) ORDER BY id
						ON CONFLICT(service, set_id, group_id) DO UPDATE SET name = excluded.name,
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
				    type, hidden, observed_at)
				SELECT :service, :set, id, "groupId", name, description, "imageUrl", type, hidden,
				    CAST(:date AS timestamptz)
				FROM jsonb_to_recordset(CAST(:trophies AS jsonb)) AS t(
				    id int, "groupId" text, name text, description text, "imageUrl" text, type text, hidden boolean)
				ORDER BY id
				ON CONFLICT(service, set_id, trophy_id) DO UPDATE SET group_id = excluded.group_id,
				    name = excluded.name, description = excluded.description, image_url = excluded.image_url,
				    type = excluded.type, hidden = excluded.hidden, observed_at = excluded.observed_at
				WHERE trophies.observed_at <= excluded.observed_at
				""")
			.param("service", service)
			.param("set", set)
			.param("date", date)
			.param("trophies", trophies)
			.update();
	}

	private void fenceLibrary(String uid, LibrarySnapshot snapshot) {
		boolean current = jdbc.sql("""
				SELECT uid FROM libraries WHERE uid = :uid AND account_id = :account
				    AND synced_at = :syncedAt FOR UPDATE
				""")
			.param("uid", uid)
			.param("account", snapshot.profile().accountId())
			.param("syncedAt", snapshot.syncedAt())
			.query(String.class)
			.optional()
			.isPresent();
		if (!current) {
			throw new ApiFailure("operation_busy", 429);
		}
	}

}
