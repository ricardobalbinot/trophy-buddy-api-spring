package dev.trophybuddy.api.publicgames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trophybuddy.api.support.ApiFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoilerArtworkTests {

	@ParameterizedTest
	@ValueSource(strings = { "png", "jpeg" })
	void returnsOnlyTheReducedArtwork(String format) throws Exception {
		var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
		var source = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, format, source)).isTrue();

		var preview = ImageIO.read(new ByteArrayInputStream(SpoilerArtwork.reduce(source.toByteArray())));
		assertThat(preview.getWidth()).isEqualTo(8);
		assertThat(preview.getHeight()).isEqualTo(8);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://image.api.playstation.com/trophy/np/fixture.jpg",
			"https://psnobj.prod.dl.playstation.net/psnobj/fixture.png",
			"https://trophy01.np.community.playstation.net/trophy/np/fixture.png" })
	void acceptsPlayStationTrophyCdnUrls(String url) {
		assertThat(SpoilerArtwork.sourceUri(url)).isEqualTo(URI.create(url));
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://image.api.playstation.com/other/fixture.jpg",
			"https://image.api.playstation.com/trophy/../other/fixture.jpg",
			"https://image.api.playstation.com/trophy/%2e%2e/other/fixture.jpg",
			"https://image.api.playstation.com.example.com/trophy/np/fixture.jpg" })
	void refusesUrlsOutsideTheTrophyCdn(String url) {
		assertThatThrownBy(() -> SpoilerArtwork.sourceUri(url)).isInstanceOf(ApiFailure.class);
	}

	@Test
	void refusesNonPlayStationArtwork() {
		assertThatThrownBy(() -> new SpoilerArtwork().preview("https://example.com/trophy.png"))
			.isInstanceOf(ApiFailure.class);
	}

}
