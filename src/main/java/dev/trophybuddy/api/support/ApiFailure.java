package dev.trophybuddy.api.support;

public final class ApiFailure extends RuntimeException {

	private final int status;

	public ApiFailure(String code, int status) {
		super(code);
		this.status = status;
	}

	public ApiFailure(String code) {
		this(code, 503);
	}

	public int status() {
		return status;
	}

}
