package dev.trophybuddy.api.psn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trophybuddy.api.support.ApiFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PsnSessionTests {

	// Public test vector created with Python AESGCM using bytes(range(32)) and
	// bytes(range(12)).
	private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private static final String PYTHON_ENVELOPE = "v1.AAECAwQFBgcICQoL.5r7m081_66H1JvfsEYkXwA."
			+ "PCC3eKaKt3X5CPOpi8laXLHlswHGWXNcGgiL6XQHZftlMpTcjYd74ADRDYjY60lBiytCoXr0wrlc8llqTIyei54e_Frxt08ZaCHYC8LubYtd-"
			+ "bpAWkNxGpib7KkLmO3ioJ03xJ5h6nz3ZErHOhAMDd8BEM6QrQe2bd02ruGDHX3-KnCvxrK4EnzDcoEP6nzyBsTTBQUho3q8c8hViLePnPbk9lCRj6UMwhCcjv26pomt_voSu5tDjFXv8PYM";

	private final PsnSession sessions = new PsnSession(null, null, null, new JsonMapper(), null, "", KEY);

	@Test
	void decryptsPythonSessionAndSealsWithFreshNonces() {
		var token = sessions.decrypt(PYTHON_ENVELOPE);
		assertThat(token.accountId()).isEqualTo("123456");
		assertThat(token.refreshToken()).isEqualTo("fixture-refresh");
		String first = sessions.encrypt(token);
		String second = sessions.encrypt(token);
		assertThat(first).isNotEqualTo(second);
		assertThat(sessions.decrypt(first)).isEqualTo(token);
	}

	@Test
	void rejectsTamperedEnvelope() {
		String tampered = PYTHON_ENVELOPE.replace(".5r7m", ".Ar7m");
		assertThatThrownBy(() -> sessions.decrypt(tampered)).isInstanceOf(ApiFailure.class)
			.hasMessage("psn_reconnect_required");
	}

}
