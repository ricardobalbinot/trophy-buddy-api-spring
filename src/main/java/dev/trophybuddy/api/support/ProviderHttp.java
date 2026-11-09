package dev.trophybuddy.api.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProviderHttp {

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private final ObjectMapper json;

	public ProviderHttp(ObjectMapper json) {
		this.json = json;
	}

	public HttpResponse<String> send(String url, Map<String, String> headers, String body, int seconds, String error) {
		try {
			var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds));
			headers.forEach(request::header);
			if (body != null) {
				request.POST(HttpRequest.BodyPublishers.ofString(body));
			}
			return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new ApiFailure(error);
		}
		catch (IOException | IllegalArgumentException failure) {
			throw new ApiFailure(error);
		}
	}

	public String getBounded(String url, int seconds, int maxBytes, String error) {
		try {
			var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds)).build();
			var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (var stream = response.body()) {
				if (response.statusCode() != 200) {
					throw new ApiFailure(error);
				}
				byte[] bytes = stream.readNBytes(maxBytes + 1);
				if (bytes.length > maxBytes) {
					throw new ApiFailure(error);
				}
				return new String(bytes, StandardCharsets.UTF_8);
			}
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new ApiFailure(error);
		}
		catch (IOException | IllegalArgumentException failure) {
			throw new ApiFailure(error);
		}
	}

	public JsonNode parse(String body, String error) {
		try {
			return json.readTree(body);
		}
		catch (RuntimeException failure) {
			throw new ApiFailure(error);
		}
	}

	public static String form(Map<String, String> values) {
		return values.entrySet()
			.stream()
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(Collectors.joining("&"));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
