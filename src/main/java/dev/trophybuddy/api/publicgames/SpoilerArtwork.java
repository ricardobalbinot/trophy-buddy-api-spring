package dev.trophybuddy.api.publicgames;

import dev.trophybuddy.api.support.ApiFailure;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.util.ConcurrentLruCache;

@Component
class SpoilerArtwork {

	private static final int MAX_BYTES = 512 * 1024;

	private static final int MAX_DIMENSION = 1024;

	private static final int PREVIEW_DIMENSION = 8;

	private final Semaphore downloads = new Semaphore(8);

	private final ConcurrentLruCache<String, byte[]> previews = new ConcurrentLruCache<>(256, this::download);

	byte[] preview(String imageUrl) {
		return previews.get(imageUrl);
	}

	/**
	 * Retain broad shapes and colors while keeping the original outside the public
	 * response.
	 */
	static byte[] reduce(byte[] bytes) throws IOException {
		try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw unavailable();
			}
			var reader = readers.next();
			try {
				reader.setInput(input, true, true);
				if (reader.getWidth(0) > MAX_DIMENSION || reader.getHeight(0) > MAX_DIMENSION) {
					throw unavailable();
				}
				var preview = new BufferedImage(PREVIEW_DIMENSION, PREVIEW_DIMENSION, BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = preview.createGraphics();
				try {
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
							RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					graphics.drawImage(reader.read(0), 0, 0, PREVIEW_DIMENSION, PREVIEW_DIMENSION, null);
				}
				finally {
					graphics.dispose();
				}
				var output = new ByteArrayOutputStream();
				ImageIO.write(preview, "png", output);
				return output.toByteArray();
			}
			finally {
				reader.dispose();
			}
		}
	}

	private byte[] download(String imageUrl) {
		URI uri = sourceUri(imageUrl);
		try {
			// A collection can lazy-load several previews together on a cold cache.
			if (!downloads.tryAcquire(3, TimeUnit.SECONDS)) {
				throw new ApiFailure("spoiler_artwork_busy", 503);
			}
		}
		catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
			throw new ApiFailure("spoiler_artwork_busy", 503);
		}
		try {
			var connection = (HttpURLConnection) uri.toURL().openConnection();
			try {
				connection.setInstanceFollowRedirects(false);
				connection.setConnectTimeout(2500);
				connection.setReadTimeout(2500);
				if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES) {
					throw unavailable();
				}
				try (var input = connection.getInputStream()) {
					var bytes = input.readNBytes(MAX_BYTES + 1);
					if (bytes.length > MAX_BYTES) {
						throw unavailable();
					}
					return reduce(bytes);
				}
			}
			finally {
				connection.disconnect();
			}
		}
		catch (IOException ignored) {
			throw unavailable();
		}
		finally {
			downloads.release();
		}
	}

	/**
	 * Only trust PlayStation CDN URLs, including artwork previously stored by older
	 * imports.
	 */
	static URI sourceUri(String imageUrl) {
		URI uri;
		try {
			uri = URI.create(imageUrl);
		}
		catch (IllegalArgumentException ignored) {
			throw unavailable();
		}
		String host = uri.getHost();
		if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || host == null) {
			throw unavailable();
		}
		boolean trophyImageApi = "image.api.playstation.com".equals(host)
				&& uri.getRawPath().matches("/trophy/[A-Za-z0-9_/-]+\\.[A-Za-z0-9]+");
		if (!trophyImageApi && !"psnobj.prod.dl.playstation.net".equals(host)
				&& !host.matches("trophy[0-9]{2}\\.np\\.community\\.playstation\\.net")) {
			throw unavailable();
		}
		return uri;
	}

	private static ApiFailure unavailable() {
		return new ApiFailure("spoiler_artwork_unavailable", 404);
	}

}
