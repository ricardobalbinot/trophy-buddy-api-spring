ALTER TABLE trophies ADD COLUMN global_earned_rate double precision
    CHECK (global_earned_rate BETWEEN 0 AND 100);

WITH latest_rates AS (
    SELECT DISTINCT ON (p.service, p.set_id, p.trophy_id)
        p.service, p.set_id, p.trophy_id, p.earned_rate
    FROM profile_trophies p
    JOIN game_details d USING (uid, game_id)
    WHERE p.earned_rate IS NOT NULL
    ORDER BY p.service, p.set_id, p.trophy_id, d.payload->>'fetchedAt' DESC NULLS LAST, p.uid, p.game_id
)
UPDATE trophies t
SET global_earned_rate = rates.earned_rate
FROM latest_rates rates
WHERE (t.service, t.set_id, t.trophy_id) = (rates.service, rates.set_id, rates.trophy_id);

ALTER TABLE public_game_editions ADD COLUMN main_story_hours double precision
    CHECK (main_story_hours > 0);
