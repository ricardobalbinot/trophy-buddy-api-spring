package dev.trophybuddy.api.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FirebaseIdentity {

	private final FirebaseApp app;

	FirebaseIdentity(@Value("${trophybuddy.firebase.project-id}") String projectId) throws IOException {
		app = projectId.isBlank() ? null
				: FirebaseApp.initializeApp(FirebaseOptions.builder()
					.setProjectId(projectId)
					.setCredentials(GoogleCredentials.getApplicationDefault())
					.build(), "trophy-buddy-spring");
	}

	/** Return the verified UID, including a revocation check against Firebase. */
	String verify(String token) {
		if (app == null) {
			throw new IdentityUnavailable();
		}
		try {
			return FirebaseAuth.getInstance(app).verifyIdToken(token, true).getUid();
		}
		catch (FirebaseAuthException failure) {
			AuthErrorCode code = failure.getAuthErrorCode();
			if (code == AuthErrorCode.INVALID_ID_TOKEN || code == AuthErrorCode.EXPIRED_ID_TOKEN
					|| code == AuthErrorCode.REVOKED_ID_TOKEN || code == AuthErrorCode.USER_DISABLED
					|| code == AuthErrorCode.USER_NOT_FOUND) {
				return null;
			}
			throw new IdentityUnavailable();
		}
		catch (IllegalArgumentException failure) {
			return null;
		}
	}

	@PreDestroy
	void close() {
		if (app != null) {
			app.delete();
		}
	}

	static final class IdentityUnavailable extends RuntimeException {

	}

}
