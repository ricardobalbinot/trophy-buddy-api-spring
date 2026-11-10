CREATE TABLE guide_evidence_revisions (
    service text NOT NULL,
    set_id text NOT NULL,
    source_url text NOT NULL,
    digest text NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    source_name text NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now(),
    rationale text NOT NULL,
    published boolean NOT NULL DEFAULT false,
    PRIMARY KEY (service, set_id, source_url, digest),
    FOREIGN KEY (service, set_id) REFERENCES trophy_sets(service, set_id)
);

CREATE UNIQUE INDEX guide_evidence_one_published_revision
    ON guide_evidence_revisions (service, set_id, source_url) WHERE published;

CREATE TABLE trophy_guide_claims (
    service text NOT NULL,
    set_id text NOT NULL,
    source_url text NOT NULL,
    digest text NOT NULL,
    trophy_id integer NOT NULL,
    kind text NOT NULL CHECK (kind IN ('missable', 'online', 'buggy')),
    applies boolean NOT NULL,
    locator text NOT NULL,
    note text NOT NULL,
    PRIMARY KEY (service, set_id, trophy_id, kind, source_url, digest),
    FOREIGN KEY (service, set_id, source_url, digest)
        REFERENCES guide_evidence_revisions(service, set_id, source_url, digest),
    FOREIGN KEY (service, set_id, trophy_id) REFERENCES trophies(service, set_id, trophy_id)
);
