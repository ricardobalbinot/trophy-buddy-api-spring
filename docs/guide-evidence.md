# Trophy guide evidence

A curated link helps a player find a walkthrough or video. A guide claim identifies a specific condition for a specific Sony trophy and records which source and section support it. Evidence is shared across library users, while PSN progress stays scoped to the authenticated user.

## Import a source revision

Open the exact game edition in a synced library first so the Sony trophy definitions exist. Review the source against its platform, region, edition and the relevant trophy names. Record only paraphrased facts that the source supports. Use the section title or video timestamp as the locator. The source URL is stable across revisions and the operator command never downloads the guide.

A revision is a JSON document like this fictional example:

```json
{
  "service": "trophy2",
  "setId": "NPWR999999999_00",
  "sourceUrl": "https://example.org/example-guide",
  "sourceName": "Example Guide",
  "rationale": "Reviewed the source against the edition and exact Sony trophy set.",
  "claims": [
    {
      "trophyId": 1,
      "kind": "missable",
      "applies": true,
      "locator": "Chapter 2 trophy section",
      "note": "Complete the optional objective before leaving the chapter."
    }
  ]
}
```

Supported kinds are `missable`, `online` and `buggy`. Record a negative claim only when the source explicitly supports it. An omitted condition is unknown. For an intentional withdrawal of every claim from a source, publish an empty claims list for its existing URL.

```sh
java -jar target/trophy-buddy-api-spring-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --trophybuddy.guide-admin=import-evidence --file=/path/to/reviewed-guide.json
```

Add `--publish` after review. Imports are transactional and idempotent by SHA-256 of the submitted document. Publishing replaces only the active revision for that source URL and trophy set. Other sources remain independent, including contradictory claims. The digest identifies the editorial manifest, not a version of the upstream page. The trophy details response reads published evidence even for cached PSN observations.

## Release prerequisite

Apply Flyway V2 before routing detail requests to this build. The readiness check requires the new evidence tables. On a fresh database, set `FLYWAY_ENABLED=true` to run V1 and V2. For an existing database created by the Python service, first back up the database and verify that its Alembic revision is `e55ca287043a`. Run the Spring image with Flyway enabled, `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and `SPRING_FLYWAY_BASELINE_VERSION=1` for that initial Flyway run. This registers V1 as the existing baseline and applies V2. Remove the baseline-on-migrate setting after verifying success. Existing PSN data and curated links are untouched by V2.

After the initial baseline, the production Railway service runs Flyway in its pre-deploy step:

```sh
java -jar /app/trophy-buddy-api.jar --spring.main.web-application-type=none --spring.flyway.enabled=true
```

## Launch checks

- For each launch title, confirm the exact Sony set and source applicability before publishing. Persona 5 Royal PS5 and Phoenix Wright: Ace Attorney Trilogy PS4 are the initial candidates.
- Verify published sources against the real game detail page, including hidden trophies, conflicting sources and games without evidence.
- Before enabling automated analysis, define approved source access, evaluate versioned candidates against reviewed claims and set limits on retries, tokens and spend.
