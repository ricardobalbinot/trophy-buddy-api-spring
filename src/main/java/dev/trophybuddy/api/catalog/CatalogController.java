package dev.trophybuddy.api.catalog;

import dev.trophybuddy.api.support.ApiFailure;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class CatalogController {

	private final CatalogService catalog;

	CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/v1/catalog")
	CatalogResponse find(@RequestParam String title, @RequestParam(defaultValue = "PS5") String platform) {
		if (title.isEmpty() || title.length() > 200 || platform.length() > 64) {
			throw new ApiFailure("invalid_catalog_query", 400);
		}
		return new CatalogResponse(catalog.find(title, platform));
	}

	record CatalogResponse(JsonNode catalog) {
	}

}
