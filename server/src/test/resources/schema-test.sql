CREATE TABLE datasets (
 id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL, status VARCHAR(24) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(), bytes BIGINT NOT NULL DEFAULT 0,
 feature_count BIGINT NOT NULL DEFAULT 0, oks_count BIGINT NOT NULL DEFAULT 0,
 error VARCHAR, sha256 VARCHAR(64), owner VARCHAR(64) NOT NULL
);
CREATE TABLE features (
 dataset_id UUID NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
 id VARCHAR(256) NOT NULL, object_type VARCHAR(40) NOT NULL,
 properties VARCHAR NOT NULL, geometry BYTEA NOT NULL,
 minx DOUBLE PRECISION NOT NULL, maxx DOUBLE PRECISION NOT NULL,
 miny DOUBLE PRECISION NOT NULL, maxy DOUBLE PRECISION NOT NULL,
 PRIMARY KEY(dataset_id,id)
);
CREATE INDEX features_type ON features(dataset_id,object_type,id);
CREATE INDEX features_xy ON features(dataset_id,minx,maxx,miny,maxy);
CREATE TABLE jobs (
 id UUID PRIMARY KEY, dataset_id UUID NOT NULL REFERENCES datasets(id),
 status VARCHAR(24) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(), updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
 progress INTEGER NOT NULL DEFAULT 0, message VARCHAR NOT NULL DEFAULT '', options VARCHAR NOT NULL,
 -- H2 1.4 TEXT is a LOB; concurrent polling/updating can fail in LobStorageMap.copyLob.
 -- VARCHAR models PostgreSQL text without that test-driver LOB race.
 summary VARCHAR, owner VARCHAR(64) NOT NULL, cancelled BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX jobs_owner ON jobs(owner,created_at DESC);
