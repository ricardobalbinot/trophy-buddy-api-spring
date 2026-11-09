package dev.trophybuddy.api.psn;

import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.OperationLeases;
import dev.trophybuddy.api.support.ProviderHttp;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class PsnSession {

	private static final String OAUTH = "https://ca.account.sony.com/api/authz/v3/oauth";

	private static final String CLIENT_ID = "09515159-7237-4370-9b40-3806e67c0891";

	// Public mobile OAuth client identification, shared with the existing adapter.
	private static final String CLIENT_AUTH = "MDk1MTUxNTktNzIzNy00MzcwLTliNDAtMzgwNmU2N2MwODkxOnVjUGprYTV0bnRCMktxc1A=";

	private static final String REDIRECT = "com.scee.psxandroid.scecompcall://redirect";

	private static final String SCOPE = "psn:mobile.v2.core psn:clientapp";

	private static final String RESOURCE = "psn-session";

	private static final byte[] CONTEXT = "trophy-buddy:psn-service-session:v1".getBytes(StandardCharsets.UTF_8);

	private final SecureRandom random = new SecureRandom();

	private final JdbcClient jdbc;

	private final OperationLeases leases;

	private final TransactionTemplate transactions;

	private final ObjectMapper json;

	private final ProviderHttp http;

	private final String bootstrap;

	private final String key;

	PsnSession(JdbcClient jdbc, OperationLeases leases, TransactionTemplate transactions, ObjectMapper json,
			ProviderHttp http, @Value("${PSN_SERVICE_NPSSO:}") String bootstrap,
			@Value("${PSN_TOKEN_ENCRYPTION_KEY:}") String key) {
		this.jdbc = jdbc;
		this.leases = leases;
		this.transactions = transactions;
		this.json = json;
		this.http = http;
		this.bootstrap = bootstrap.trim();
		this.key = key;
	}

	record Token(String accountId, String onlineId, String accessToken, String refreshToken, long accessExpiresAt,
			long refreshExpiresAt) {
	}

	private record Stored(String encrypted, String digest, long expires) {
	}

	private Stored stored() {
		return jdbc.sql("""
				SELECT encrypted, bootstrap_digest, access_expires_at
				FROM service_sessions WHERE provider = 'psn'
				""")
			.query((row, index) -> new Stored(row.getString(1), row.getString(2), row.getLong(3)))
			.optional()
			.orElse(null);
	}

	Token get() {
		String digest = bootstrap.isEmpty() ? null : digest(bootstrap);
		Stored current = stored();
		if (usable(current, digest)) {
			return decrypt(current.encrypted());
		}

		return leases.execute(RESOURCE, 90, 5, operation -> {
			Stored state = stored();
			if (usable(state, digest)) {
				Token token = decrypt(state.encrypted());
				leases.release(RESOURCE, operation, 0);
				return token;
			}
			Token previous = state == null ? null : decrypt(state.encrypted());
			Token renewed = previous == null || (digest != null && !digest.equals(state.digest())) ? bootstrap()
					: refresh(previous);
			String encrypted = encrypt(renewed);
			transactions.executeWithoutResult(status -> {
				leases.fence(RESOURCE, operation);
				jdbc.sql("""
						INSERT INTO service_sessions(provider, encrypted, bootstrap_digest, access_expires_at)
						VALUES ('psn', :encrypted, :digest, :expires)
						ON CONFLICT(provider) DO UPDATE SET encrypted = excluded.encrypted,
						  bootstrap_digest = excluded.bootstrap_digest, access_expires_at = excluded.access_expires_at
						""")
					.param("encrypted", encrypted)
					.param("expires", renewed.accessExpiresAt())
					.param("digest", digest != null ? digest : state == null ? null : state.digest())
					.update();
				leases.release(RESOURCE, operation, 0);
			});
			return renewed;
		});
	}

	private boolean usable(Stored state, String digest) {
		return state != null && state.expires() > System.currentTimeMillis() + 60_000
				&& (digest == null || digest.equals(state.digest()));
	}

	private Token bootstrap() {
		if (!bootstrap.matches("[A-Za-z0-9_-]{64}")) {
			throw reconnect();
		}
		String query = ProviderHttp.form(Map.of("access_type", "offline", "client_id", CLIENT_ID, "redirect_uri",
				REDIRECT, "response_type", "code", "scope", SCOPE));
		var response = http.send(OAUTH + "/authorize?" + query, Map.of("Cookie", "npsso=" + bootstrap), null, 15,
				"psn_session_unavailable");
		String code = authorizationCode(response.headers().firstValue("Location").orElse(""));
		JsonNode body = exchange(Map.of("code", code, "grant_type", "authorization_code", "redirect_uri", REDIRECT));
		String account = subject(body.path("id_token").asText(""));
		if (!account.matches("[0-9]+")) {
			throw reconnect();
		}
		var profile = http.send(
				"https://m.np.playstation.com/api/userProfile/v1/internal/users/" + account + "/profiles",
				Map.of("Authorization", "Bearer " + body.path("access_token").asText()), null, 15,
				"psn_session_unavailable");
		JsonNode identity = http.parse(profile.body(), "psn_session_unavailable");
		String onlineId = identity.path("onlineId").asText("");
		if (profile.statusCode() != 200 || !identity.path("isMe").asBoolean(false) || onlineId.isBlank()) {
			throw reconnect();
		}
		return token(body, account, onlineId);
	}

	private Token refresh(Token previous) {
		if (previous.refreshExpiresAt() <= System.currentTimeMillis()) {
			throw reconnect();
		}
		JsonNode body = exchange(
				Map.of("refresh_token", previous.refreshToken(), "grant_type", "refresh_token", "scope", SCOPE));
		if (body.hasNonNull("id_token") && !previous.accountId().equals(subject(body.path("id_token").asText()))) {
			throw reconnect();
		}
		return token(body, previous.accountId(), previous.onlineId());
	}

	private JsonNode exchange(Map<String, String> values) {
		var response = http.send(OAUTH + "/token",
				Map.of("Authorization", "Basic " + CLIENT_AUTH, "Content-Type", "application/x-www-form-urlencoded"),
				ProviderHttp.form(values) + "&token_format=jwt", 15, "psn_session_unavailable");
		if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
			throw reconnect();
		}
		if (response.statusCode() != 200) {
			throw new ApiFailure("psn_session_unavailable");
		}
		JsonNode body = http.parse(response.body(), "psn_session_unavailable");
		if (body.path("access_token").asText("").isBlank() || body.path("refresh_token").asText("").isBlank()
				|| !body.path("expires_in").isIntegralNumber()
				|| !body.path("refresh_token_expires_in").isIntegralNumber()) {
			throw reconnect();
		}
		return body;
	}

	private static Token token(JsonNode body, String account, String onlineId) {
		long now = System.currentTimeMillis();
		return new Token(account, onlineId, body.path("access_token").asText(), body.path("refresh_token").asText(),
				now + body.path("expires_in").asLong() * 1000,
				now + body.path("refresh_token_expires_in").asLong() * 1000);
	}

	private static String authorizationCode(String location) {
		try {
			String query = URI.create(location).getRawQuery();
			if (query != null) {
				for (String field : query.split("&")) {
					if (field.startsWith("code=") && field.length() > 5) {
						return URLDecoder.decode(field.substring(5), StandardCharsets.UTF_8);
					}
				}
			}
		}
		catch (IllegalArgumentException ignored) {
			// Invalid redirects have no usable authorization code.
		}
		throw reconnect();
	}

	private String subject(String token) {
		try {
			return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("sub").asText("");
		}
		catch (RuntimeException failure) {
			throw reconnect();
		}
	}

	private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(key);
		}
		catch (IllegalArgumentException failure) {
			throw new ApiFailure("psn_encryption_not_configured");
		}
		if (decoded.length != 32) {
			throw new ApiFailure("psn_encryption_not_configured");
		}
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, new SecretKeySpec(decoded, "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(CONTEXT);
		return cipher;
	}

	/**
	 * Preserve the Python envelope format so the same encrypted session can be migrated.
	 */
	String encrypt(Token token) {
		try {
			byte[] nonce = new byte[12];
			random.nextBytes(nonce);
			byte[] sealed = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(json.writeValueAsBytes(token));
			int tag = sealed.length - 16;
			var encoder = Base64.getUrlEncoder().withoutPadding();
			return "v1." + encoder.encodeToString(nonce) + "."
					+ encoder.encodeToString(Arrays.copyOfRange(sealed, tag, sealed.length)) + "."
					+ encoder.encodeToString(Arrays.copyOf(sealed, tag));
		}
		catch (GeneralSecurityException failure) {
			throw new ApiFailure("psn_session_unavailable");
		}
	}

	Token decrypt(String value) {
		try {
			String[] parts = value.split("\\.");
			if (parts.length != 4 || !parts[0].equals("v1")) {
				throw reconnect();
			}
			var decoder = Base64.getUrlDecoder();
			byte[] nonce = decoder.decode(parts[1]);
			byte[] tag = decoder.decode(parts[2]);
			byte[] payload = decoder.decode(parts[3]);
			byte[] sealed = Arrays.copyOf(payload, payload.length + tag.length);
			System.arraycopy(tag, 0, sealed, payload.length, tag.length);
			Token token = json.readValue(cipher(Cipher.DECRYPT_MODE, nonce).doFinal(sealed), Token.class);
			if (token.accountId() == null || !token.accountId().matches("[0-9]+") || token.onlineId() == null
					|| token.accessToken() == null || token.refreshToken() == null || token.accessExpiresAt() <= 0
					|| token.refreshExpiresAt() <= 0) {
				throw reconnect();
			}
			return token;
		}
		catch (GeneralSecurityException | RuntimeException failure) {
			throw reconnect();
		}
	}

	private static String digest(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static ApiFailure reconnect() {
		return new ApiFailure("psn_reconnect_required");
	}

}
