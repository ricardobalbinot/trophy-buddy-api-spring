package dev.trophybuddy.api.library;

import dev.trophybuddy.api.psn.PsnClient;
import dev.trophybuddy.api.support.ApiFailure;
import dev.trophybuddy.api.support.OperationLeases;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
class LibrarySync {

	private final PsnClient psn;

	private final LibraryWriter writer;

	private final OperationLeases leases;

	LibrarySync(PsnClient psn, LibraryWriter writer, OperationLeases leases) {
		this.psn = psn;
		this.writer = writer;
		this.leases = leases;
	}

	LibrarySnapshot synchronize(String uid, String onlineId) {
		try {
			return leases.execute("library:" + uid, 600, 15, operation -> {
				LibrarySnapshot snapshot = psn.importLibrary(onlineId);
				writer.publish(uid, snapshot, operation);
				return new LibrarySnapshot(snapshot.version(), snapshot.profile(), snapshot.syncedAt(),
						snapshot.games().stream().filter(game -> game.trophySetId() != null).toList());
			});
		}
		catch (DataAccessException | ArithmeticException failure) {
			throw new ApiFailure("library_sync_failed");
		}
	}

}
