-- Baseline of the final Python schema, excluding Alembic metadata.
CREATE TABLE public.catalog_cache (
    key character varying(64) NOT NULL,
    payload jsonb,
    expires_at timestamp with time zone NOT NULL
);

CREATE TABLE public.detail_trophy_groups (
    uid character varying(128) NOT NULL,
    game_id character varying(128) NOT NULL,
    group_id character varying(32) NOT NULL,
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL
);

CREATE TABLE public.game_details (
    uid character varying(128) NOT NULL,
    game_id character varying(128) NOT NULL,
    payload jsonb NOT NULL
);

CREATE TABLE public.guide_analysis_requests (
    entry_id character varying(300) NOT NULL,
    content_digest character varying(64) NOT NULL,
    schema_version character varying(16) NOT NULL,
    state character varying(32) NOT NULL,
    reason character varying(100),
    requested_at timestamp with time zone NOT NULL
);

CREATE TABLE public.guide_bindings (
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    entry_id character varying(300) NOT NULL,
    version_digest character varying(64) NOT NULL,
    content_digest character varying(64) NOT NULL,
    game_title text NOT NULL,
    platform character varying(16) NOT NULL,
    reviewed_at timestamp with time zone NOT NULL,
    rationale text NOT NULL,
    match_method character varying(16) DEFAULT 'manual'::character varying NOT NULL
);

CREATE TABLE public.guide_discoveries (
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    state character varying(32) NOT NULL,
    retry_at timestamp with time zone NOT NULL
);

CREATE TABLE public.guide_index_versions (
    digest character varying(64) NOT NULL,
    source_url text NOT NULL,
    retrieved_at timestamp with time zone NOT NULL
);

CREATE TABLE public.indexed_guides (
    entry_id character varying(300) NOT NULL,
    version_digest character varying(64) NOT NULL,
    content_digest character varying(64) NOT NULL,
    active boolean NOT NULL,
    source character varying(32) NOT NULL,
    source_name character varying(40) NOT NULL,
    title text NOT NULL,
    game_name text NOT NULL,
    url text NOT NULL,
    attributes integer NOT NULL,
    external_game_ids jsonb NOT NULL,
    authors jsonb NOT NULL,
    difficulty double precision,
    playthroughs double precision,
    platinum_hours double precision,
    trophy_count integer,
    CONSTRAINT ck_guides_difficulty CHECK (((difficulty IS NULL) OR ((difficulty >= (0)::double precision) AND (difficulty <= (10)::double precision)))),
    CONSTRAINT ck_guides_hours CHECK (((platinum_hours IS NULL) OR (platinum_hours > (0)::double precision))),
    CONSTRAINT ck_guides_runs CHECK (((playthroughs IS NULL) OR (playthroughs > (0)::double precision))),
    CONSTRAINT ck_guides_trophy_count CHECK (((trophy_count IS NULL) OR (trophy_count > 0)))
);

CREATE TABLE public.libraries (
    uid character varying(128) NOT NULL,
    account_id character varying(32) NOT NULL,
    synced_at character varying(40) NOT NULL
);

CREATE TABLE public.library_games (
    uid character varying(128) NOT NULL,
    game_id character varying(128) NOT NULL,
    title text NOT NULL,
    platform character varying(64) NOT NULL,
    image_url text,
    title_ids jsonb NOT NULL,
    trophy_set_id character varying(64),
    trophy_service character varying(16),
    earned integer,
    total integer,
    progress integer,
    platinum boolean NOT NULL,
    last_played_at timestamp with time zone,
    trophy_progress_updated_at timestamp with time zone,
    CONSTRAINT ck_games_earned CHECK (((earned IS NULL) OR (earned >= 0))),
    CONSTRAINT ck_games_progress CHECK (((progress IS NULL) OR ((progress >= 0) AND (progress <= 100)))),
    CONSTRAINT ck_games_service CHECK (((trophy_service IS NULL) OR ((trophy_service)::text = ANY ((ARRAY['trophy'::character varying, 'trophy2'::character varying])::text[])))),
    CONSTRAINT ck_games_total CHECK (((total IS NULL) OR (total >= 0)))
);

CREATE TABLE public.operation_leases (
    resource character varying(256) NOT NULL,
    operation_id character varying(32) NOT NULL,
    lease_until double precision NOT NULL,
    retry_after double precision NOT NULL
);

CREATE TABLE public.profile_trophies (
    uid character varying(128) NOT NULL,
    game_id character varying(128) NOT NULL,
    trophy_id integer NOT NULL,
    account_id character varying(32) NOT NULL,
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    earned boolean,
    earned_at character varying(40),
    earned_rate double precision,
    CONSTRAINT profile_trophies_check CHECK (((earned_at IS NULL) OR (earned IS TRUE))),
    CONSTRAINT profile_trophies_earned_rate_check CHECK (((earned_rate IS NULL) OR ((earned_rate >= (0)::double precision) AND (earned_rate <= (100)::double precision))))
);

CREATE TABLE public.psn_profiles (
    account_id character varying(32) NOT NULL,
    online_id character varying(32) NOT NULL,
    avatar_url text,
    trophy_summary jsonb
);

CREATE TABLE public.service_sessions (
    provider character varying(32) NOT NULL,
    encrypted text NOT NULL,
    bootstrap_digest character varying(64),
    access_expires_at bigint NOT NULL
);

CREATE TABLE public.trophies (
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    trophy_id integer NOT NULL,
    group_id character varying(32) NOT NULL,
    name text,
    description text,
    image_url text,
    type character varying(16) NOT NULL,
    hidden boolean NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    CONSTRAINT trophies_trophy_id_check CHECK ((trophy_id >= 0)),
    CONSTRAINT trophies_type_check CHECK (((type)::text = ANY ((ARRAY['bronze'::character varying, 'silver'::character varying, 'gold'::character varying, 'platinum'::character varying])::text[])))
);

CREATE TABLE public.trophy_groups (
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    group_id character varying(32) NOT NULL,
    name text,
    observed_at timestamp with time zone NOT NULL
);

CREATE TABLE public.trophy_resources (
    resource_id character varying(64) NOT NULL,
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    trophy_id integer NOT NULL,
    title character varying(180) NOT NULL,
    source_name character varying(100) NOT NULL,
    source_url text NOT NULL,
    kind character varying(16) NOT NULL,
    language character varying(16) NOT NULL,
    contains_spoilers boolean NOT NULL,
    published boolean NOT NULL,
    reviewed_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_resources_https CHECK ((source_url ~~ 'https://%%'::text)),
    CONSTRAINT ck_resources_kind CHECK (((kind)::text = ANY ((ARRAY['video'::character varying, 'article'::character varying])::text[])))
);

CREATE TABLE public.trophy_sets (
    service character varying(16) NOT NULL,
    set_id character varying(64) NOT NULL,
    CONSTRAINT trophy_sets_service_check CHECK (((service)::text = ANY ((ARRAY['trophy'::character varying, 'trophy2'::character varying])::text[])))
);

CREATE TABLE public.users (
    uid character varying(128) NOT NULL,
    display_name character varying(100)
);

ALTER TABLE ONLY public.catalog_cache
    ADD CONSTRAINT catalog_cache_pkey PRIMARY KEY (key);

ALTER TABLE ONLY public.detail_trophy_groups
    ADD CONSTRAINT detail_trophy_groups_pkey PRIMARY KEY (uid, game_id, group_id);

ALTER TABLE ONLY public.game_details
    ADD CONSTRAINT game_details_pkey PRIMARY KEY (uid, game_id);

ALTER TABLE ONLY public.guide_analysis_requests
    ADD CONSTRAINT guide_analysis_requests_pkey PRIMARY KEY (entry_id, content_digest, schema_version);

ALTER TABLE ONLY public.guide_bindings
    ADD CONSTRAINT guide_bindings_pkey PRIMARY KEY (service, set_id);

ALTER TABLE ONLY public.guide_discoveries
    ADD CONSTRAINT guide_discoveries_pkey PRIMARY KEY (service, set_id);

ALTER TABLE ONLY public.guide_index_versions
    ADD CONSTRAINT guide_index_versions_pkey PRIMARY KEY (digest);

ALTER TABLE ONLY public.indexed_guides
    ADD CONSTRAINT indexed_guides_pkey PRIMARY KEY (entry_id);

ALTER TABLE ONLY public.libraries
    ADD CONSTRAINT libraries_pkey PRIMARY KEY (uid);

ALTER TABLE ONLY public.library_games
    ADD CONSTRAINT library_games_pkey PRIMARY KEY (uid, game_id);

ALTER TABLE ONLY public.operation_leases
    ADD CONSTRAINT operation_leases_pkey PRIMARY KEY (resource);

ALTER TABLE ONLY public.profile_trophies
    ADD CONSTRAINT profile_trophies_pkey PRIMARY KEY (uid, game_id, trophy_id);

ALTER TABLE ONLY public.psn_profiles
    ADD CONSTRAINT psn_profiles_pkey PRIMARY KEY (account_id);

ALTER TABLE ONLY public.service_sessions
    ADD CONSTRAINT service_sessions_pkey PRIMARY KEY (provider);

ALTER TABLE ONLY public.trophies
    ADD CONSTRAINT trophies_pkey PRIMARY KEY (service, set_id, trophy_id);

ALTER TABLE ONLY public.trophy_groups
    ADD CONSTRAINT trophy_groups_pkey PRIMARY KEY (service, set_id, group_id);

ALTER TABLE ONLY public.trophy_resources
    ADD CONSTRAINT trophy_resources_pkey PRIMARY KEY (resource_id);

ALTER TABLE ONLY public.trophy_sets
    ADD CONSTRAINT trophy_sets_pkey PRIMARY KEY (service, set_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (uid);

CREATE INDEX ix_resources_trophy ON public.trophy_resources USING btree (service, set_id, trophy_id);

ALTER TABLE ONLY public.detail_trophy_groups
    ADD CONSTRAINT detail_trophy_groups_service_set_id_group_id_fkey FOREIGN KEY (service, set_id, group_id) REFERENCES public.trophy_groups(service, set_id, group_id);

ALTER TABLE ONLY public.detail_trophy_groups
    ADD CONSTRAINT detail_trophy_groups_uid_game_id_fkey FOREIGN KEY (uid, game_id) REFERENCES public.game_details(uid, game_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.game_details
    ADD CONSTRAINT fk_details_library_game FOREIGN KEY (uid, game_id) REFERENCES public.library_games(uid, game_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.libraries
    ADD CONSTRAINT fk_libraries_user FOREIGN KEY (uid) REFERENCES public.users(uid) ON DELETE CASCADE;

ALTER TABLE ONLY public.guide_analysis_requests
    ADD CONSTRAINT guide_analysis_requests_entry_id_fkey FOREIGN KEY (entry_id) REFERENCES public.indexed_guides(entry_id);

ALTER TABLE ONLY public.guide_bindings
    ADD CONSTRAINT guide_bindings_entry_id_fkey FOREIGN KEY (entry_id) REFERENCES public.indexed_guides(entry_id);

ALTER TABLE ONLY public.guide_bindings
    ADD CONSTRAINT guide_bindings_service_set_id_fkey FOREIGN KEY (service, set_id) REFERENCES public.trophy_sets(service, set_id);

ALTER TABLE ONLY public.guide_bindings
    ADD CONSTRAINT guide_bindings_version_digest_fkey FOREIGN KEY (version_digest) REFERENCES public.guide_index_versions(digest);

ALTER TABLE ONLY public.guide_discoveries
    ADD CONSTRAINT guide_discoveries_service_set_id_fkey FOREIGN KEY (service, set_id) REFERENCES public.trophy_sets(service, set_id);

ALTER TABLE ONLY public.indexed_guides
    ADD CONSTRAINT indexed_guides_version_digest_fkey FOREIGN KEY (version_digest) REFERENCES public.guide_index_versions(digest);

ALTER TABLE ONLY public.libraries
    ADD CONSTRAINT libraries_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.psn_profiles(account_id);

ALTER TABLE ONLY public.library_games
    ADD CONSTRAINT library_games_uid_fkey FOREIGN KEY (uid) REFERENCES public.libraries(uid) ON DELETE CASCADE;

ALTER TABLE ONLY public.profile_trophies
    ADD CONSTRAINT profile_trophies_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.psn_profiles(account_id);

ALTER TABLE ONLY public.profile_trophies
    ADD CONSTRAINT profile_trophies_service_set_id_trophy_id_fkey FOREIGN KEY (service, set_id, trophy_id) REFERENCES public.trophies(service, set_id, trophy_id);

ALTER TABLE ONLY public.profile_trophies
    ADD CONSTRAINT profile_trophies_uid_game_id_fkey FOREIGN KEY (uid, game_id) REFERENCES public.game_details(uid, game_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.trophies
    ADD CONSTRAINT trophies_service_set_id_group_id_fkey FOREIGN KEY (service, set_id, group_id) REFERENCES public.trophy_groups(service, set_id, group_id);

ALTER TABLE ONLY public.trophy_groups
    ADD CONSTRAINT trophy_groups_service_set_id_fkey FOREIGN KEY (service, set_id) REFERENCES public.trophy_sets(service, set_id);

ALTER TABLE ONLY public.trophy_resources
    ADD CONSTRAINT trophy_resources_service_set_id_trophy_id_fkey FOREIGN KEY (service, set_id, trophy_id) REFERENCES public.trophies(service, set_id, trophy_id);
