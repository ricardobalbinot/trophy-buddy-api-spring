package dev.trophybuddy.api.security;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FirebaseIdentity {

	private final FirebaseApp app;

	FirebaseIdentity(@Value("${trophybuddy.firebase.project-id}") String projectId,
			@Value("${FIREBASE_CLIENT_EMAIL:}") String email, @Value("${FIREBASE_PRIVATE_KEY:}") String privateKey)
			throws IOException {
		app = projectId.isBlank() ? null
				: FirebaseApp.initializeApp(FirebaseOptions.builder()
					.setProjectId(projectId)
					.setCredentials(credentials(email, privateKey))
					.build(), "trophy-buddy-spring");
	}

	private static GoogleCredentials credentials(String email, String privateKey) throws IOException {
		if (email.isBlank() && privateKey.isBlank()) {
			return GoogleCredentials.getApplicationDefault();
		}
		if (email.isBlank() || privateKey.isBlank()) {
			throw new IllegalStateException("Firebase service-account credentials are incomplete");
		}
		return ServiceAccountCredentials.fromPkcs8(null, email, privateKey.replace("\\n", "\n"), null, List.of());
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
