CREATE TABLE public_game_editions (
    slug varchar(120) PRIMARY KEY,
    service varchar(16) NOT NULL,
    set_id varchar(64) NOT NULL,
    title text NOT NULL,
    platform varchar(16) NOT NULL,
    cover_url text,
    FOREIGN KEY (service, set_id) REFERENCES trophy_sets(service, set_id),
    UNIQUE (service, set_id)
);

CREATE TABLE public_guide_revisions (
    slug varchar(120) NOT NULL REFERENCES public_game_editions(slug),
    revision integer NOT NULL CHECK (revision > 0),
    content jsonb NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (slug, revision)
);
