package dev.trophybuddy.api.support;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OperationLeases {

	private final JdbcClient jdbc;

	public OperationLeases(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Keep provider calls outside database transactions and release only our own claim.
	 */
	public <T> T execute(String resource, int duration, int cooldown, Function<String, T> work) {
		String id = UUID.randomUUID().toString().replace("-", "");
		boolean acquired = jdbc.sql("""
				INSERT INTO operation_leases(resource, operation_id, lease_until, retry_after)
				VALUES (:resource, :id, extract(epoch from clock_timestamp()) + :duration, 0)
				ON CONFLICT(resource) DO UPDATE
				SET operation_id = excluded.operation_id, lease_until = excluded.lease_until
				WHERE operation_leases.lease_until <= extract(epoch from clock_timestamp())
				  AND operation_leases.retry_after <= extract(epoch from clock_timestamp())
				RETURNING resource
				""")
			.param("resource", resource)
			.param("id", id)
			.param("duration", duration)
			.query(String.class)
			.optional()
			.isPresent();
		if (!acquired) {
			throw new ApiFailure("operation_busy", 429);
		}
		try {
			return work.apply(id);
		}
		catch (RuntimeException failure) {
			try {
				release(resource, id, cooldown);
			}
			catch (RuntimeException cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/** Call in the same transaction that publishes the operation result. */
	public void fence(String resource, String id) {
		boolean owned = jdbc.sql("""
				SELECT resource FROM operation_leases
				WHERE resource = :resource AND operation_id = :id
				  AND lease_until > extract(epoch from clock_timestamp())
				FOR UPDATE
				""").param("resource", resource).param("id", id).query(String.class).optional().isPresent();
		if (!owned) {
			throw new ApiFailure("operation_busy", 429);
		}
	}

	public void release(String resource, String id, int cooldown) {
		jdbc.sql("""
				UPDATE operation_leases SET lease_until = 0,
				    retry_after = extract(epoch from clock_timestamp()) + :cooldown
				WHERE resource = :resource AND operation_id = :id
				""").param("resource", resource).param("id", id).param("cooldown", cooldown).update();
	}

}
