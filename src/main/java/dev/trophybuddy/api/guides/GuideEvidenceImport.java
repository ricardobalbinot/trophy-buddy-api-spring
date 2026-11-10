package dev.trophybuddy.api.guides;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
class GuideEvidenceImport {

	private static final int MAX_BYTES = 256 * 1024;

	private static final Set<String> KINDS = Set.of("missable", "online", "buggy");

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final TransactionTemplate transactions;

	GuideEvidenceImport(JdbcClient jdbc, ObjectMapper json, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.json = json;
		this.transactions = transactions;
	}

	/**
	 * Import one reviewed source revision for an exact Sony trophy set. Draft imports
	 * leave the currently published source revision in place.
	 */
	String importFile(Path file, boolean publish) throws IOException {
		byte[] content;
		try (var input = Files.newInputStream(file)) {
			content = input.readNBytes(MAX_BYTES + 1);
		}
		if (content.length > MAX_BYTES) {
			throw new IllegalArgumentException("Evidence file exceeds 256 KiB");
		}

		var revision = json.readValue(content, Revision.class);
		validate(revision);
		String digest = digest(content);
		transactions.executeWithoutResult(status -> save(revision, digest, publish));
		return digest;
	}

	private void save(Revision revision, String digest, boolean publish) {
		// Serialize edits to the same trophy set, including revisions of the same source.
		jdbc.sql("SELECT set_id FROM trophy_sets WHERE service = :service AND set_id = :set FOR NO KEY UPDATE")
			.param("service", revision.service())
			.param("set", revision.setId())
			.query(String.class)
			.optional()
			.orElseThrow(() -> new IllegalArgumentException("Import the exact PSN trophy set first"));

		int inserted = jdbc.sql("""
				INSERT INTO guide_evidence_revisions(service, set_id, source_url, digest, source_name, rationale)
				VALUES (:service, :set, :url, :digest, :source, :rationale) ON CONFLICT DO NOTHING
				""")
			.param("service", revision.service())
			.param("set", revision.setId())
			.param("url", revision.sourceUrl())
			.param("digest", digest)
			.param("source", revision.sourceName())
			.param("rationale", revision.rationale())
			.update();
		if (inserted == 1) {
			jdbc.sql("""
					INSERT INTO trophy_guide_claims(service, set_id, source_url, digest, trophy_id,
					    kind, applies, locator, note)
					SELECT :service, :set, :url, :digest, c."trophyId", c.kind, c.applies, c.locator, c.note
					FROM jsonb_to_recordset(CAST(:claims AS jsonb)) AS c(
					    "trophyId" int, kind text, applies boolean, locator text, note text)
					""")
				.param("service", revision.service())
				.param("set", revision.setId())
				.param("url", revision.sourceUrl())
				.param("digest", digest)
				.param("claims", json.writeValueAsString(revision.claims()))
				.update();
		}
		if (publish) {
			publish(revision, digest);
		}
	}

	private void publish(Revision revision, String digest) {
		jdbc.sql("""
				UPDATE guide_evidence_revisions SET published = false
				WHERE service = :service AND set_id = :set AND source_url = :url AND published AND digest <> :digest
				""")
			.param("service", revision.service())
			.param("set", revision.setId())
			.param("url", revision.sourceUrl())
			.param("digest", digest)
			.update();
		jdbc.sql("""
				UPDATE guide_evidence_revisions SET published = true, reviewed_at = now()
				WHERE service = :service AND set_id = :set AND source_url = :url AND digest = :digest AND NOT published
				""")
			.param("service", revision.service())
			.param("set", revision.setId())
			.param("url", revision.sourceUrl())
			.param("digest", digest)
			.update();
	}

	private static void validate(Revision revision) {
		if (revision == null || revision.service() == null || !revision.service().matches("trophy|trophy2")
				|| revision.setId() == null || !revision.setId().matches("NPWR[0-9]+_[0-9]{2}")
				|| !validText(revision.sourceName(), 1, 100) || !validText(revision.sourceUrl(), 1, 2000)
				|| !validText(revision.rationale(), 30, 1000) || revision.claims() == null
				|| revision.claims().size() > 500) {
			throw new IllegalArgumentException("Invalid guide evidence revision");
		}

		URI uri = URI.create(revision.sourceUrl());
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getFragment() != null) {
			throw new IllegalArgumentException("Use an HTTPS guide URL without credentials or a fragment");
		}
		for (var claim : revision.claims()) {
			if (claim == null || claim.trophyId() == null || claim.trophyId() < 0 || claim.kind() == null
					|| !KINDS.contains(claim.kind()) || claim.applies() == null || !validText(claim.locator(), 1, 250)
					|| !validText(claim.note(), 1, 500)) {
				throw new IllegalArgumentException("Invalid trophy guide claim");
			}
		}
	}

	private static boolean validText(String value, int min, int max) {
		return value != null && value.strip().length() >= min && value.length() <= max;
	}

	private static String digest(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	record Revision(String service, String setId, String sourceUrl, String sourceName, String rationale,
			List<Claim> claims) {
	}

	record Claim(Integer trophyId, String kind, Boolean applies, String locator, String note) {
	}

}
