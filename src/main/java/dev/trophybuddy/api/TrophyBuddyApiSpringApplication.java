package dev.trophybuddy.api;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TrophyBuddyApiSpringApplication {

	public static void main(String[] args) {
		var application = new SpringApplication(TrophyBuddyApiSpringApplication.class);
		if (Arrays.stream(args).anyMatch(arg -> arg.startsWith("--trophybuddy.guide-admin="))) {
			application.setWebApplicationType(WebApplicationType.NONE);
			try (var context = application.run(args)) {
				return;
			}
		}
		application.run(args);
	}

}
