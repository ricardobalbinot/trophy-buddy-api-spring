package dev.trophybuddy.api.guides;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TROPHY_VERIFY_WRITES", matches = "true")
class GuideEvidenceIntegrationTests {

	@Autowired
	private GuideEvidenceImport evidence;

	@Autowired
	private JdbcClient jdbc;

	@TempDir
	private Path input;

	@Test
	void keepsDisagreementsAndOnlyReplacesThePublishedRevisionForItsOwnSource() throws Exception {
		String database = jdbc.sql("SELECT current_database()").query(String.class).single();
		if (!database.endsWith("_spring_test")) {
			throw new IllegalStateException("Write verification requires a dedicated _spring_test database");
		}

		jdbc.sql("INSERT INTO trophy_sets VALUES ('trophy2', 'NPWR999999999_00')").update();
		jdbc.sql("""
				INSERT INTO trophy_groups(service, set_id, group_id, observed_at)
				VALUES ('trophy2', 'NPWR999999999_00', 'default', now())
				""").update();
		jdbc.sql("""
				INSERT INTO trophies(service, set_id, trophy_id, group_id, type, hidden, observed_at)
				VALUES ('trophy2', 'NPWR999999999_00', 1, 'default', 'gold', false, now())
				""").update();

		Path original = write("one.json", "first", true, 1);
		Path other = write("two.json", "second", false, 1);
		Path correction = write("corrected.json", "first", false, 1);

		String digest = evidence.importFile(original, true);
		assertEquals(digest, evidence.importFile(original, true));
		evidence.importFile(other, true);
		evidence.importFile(correction, false);
		assertEquals(List.of(true, false), publishedClaims());

		evidence.importFile(correction, true);
		assertEquals(List.of(false, false), publishedClaims());
		assertEquals(3,
				jdbc.sql("SELECT count(*) FROM guide_evidence_revisions WHERE set_id = 'NPWR999999999_00'")
					.query(Integer.class)
					.single());

		Path invalid = write("invalid.json", "first", true, 99);
		assertThrows(DataIntegrityViolationException.class, () -> evidence.importFile(invalid, true));
	}

	private List<Boolean> publishedClaims() {
		return jdbc.sql("""
				SELECT c.applies FROM trophy_guide_claims c
				JOIN guide_evidence_revisions r USING (service, set_id, source_url, digest)
				WHERE c.set_id = 'NPWR999999999_00' AND r.published ORDER BY c.source_url
				""").query(Boolean.class).list();
	}

	private Path write(String filename, String source, boolean applies, int trophy) throws Exception {
		return Files.writeString(this.input.resolve(filename), """
				{
				  "service": "trophy2", "setId": "NPWR999999999_00",
				  "sourceName": "%s", "sourceUrl": "https://example.org/%s",
				  "rationale": "Reviewed the exact trophy set in this fixture.",
				  "claims": [
				    {"trophyId": %d, "kind": "missable", "applies": %b,
				     "locator": "Trophy section", "note": "Fixture condition."}
				  ]
				}
				""".formatted(source, source, trophy, applies));
	}

}
