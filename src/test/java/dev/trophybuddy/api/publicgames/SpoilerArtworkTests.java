package dev.trophybuddy.api.publicgames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trophybuddy.api.support.ApiFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SpoilerArtworkTests {

	@Test
	void returnsOnlyTheReducedArtwork() throws Exception {
		var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		var source = new ByteArrayOutputStream();
		ImageIO.write(image, "png", source);

		var preview = ImageIO.read(new ByteArrayInputStream(SpoilerArtwork.reduce(source.toByteArray())));
		assertThat(preview.getWidth()).isEqualTo(8);
		assertThat(preview.getHeight()).isEqualTo(8);
	}

	@Test
	void refusesNonPlayStationArtwork() {
		assertThatThrownBy(() -> new SpoilerArtwork().preview("https://example.com/trophy.png"))
			.isInstanceOf(ApiFailure.class);
	}

}
