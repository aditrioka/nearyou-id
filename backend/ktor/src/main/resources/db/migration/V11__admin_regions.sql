-- V11: admin_regions reference table + posts_set_city_tg BEFORE INSERT trigger.
--
-- Session 1 (this migration lands without the ~540-row polygon seed — Session 2 adds the
-- seed + a proper licensing/attribution header. See openspec/changes/global-timeline-with-region-polygons/DEFERRED.md).
--
-- Consumers of V11:
--   - nearby-timeline: SELECT projects p.city_name from visible_posts.
--   - following-timeline: SELECT projects p.city_name from visible_posts.
--   - global-timeline: endpoint projects p.city_name from visible_posts.
--
-- First spatially-seeded reference table + first BEFORE INSERT trigger in the Flyway history.
--
-- posts.city_name TEXT and posts.city_match_type VARCHAR(16) already exist from V4
-- (backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:22-23). V11 is strictly
-- additive at the reference-data layer: new admin_regions table, new trigger, idempotent
-- visible_posts view refresh. ZERO ALTER TABLE posts.
--
-- Coastal polygons (once seeded) will carry a 12 nautical mile (~22 km) maritime buffer baked
-- into the stored geom column — the trigger treats all polygons uniformly at query time. The
-- buffering is a dataset-prep concern (ST_Buffer in import script), not a query concern.

CREATE TABLE admin_regions (
    id              INT PRIMARY KEY,
    name            TEXT NOT NULL,
    level           TEXT NOT NULL CHECK (level IN ('province', 'kabupaten_kota')),
    parent_id       INT REFERENCES admin_regions(id),
    geom            GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL,
    geom_centroid   GEOGRAPHY(POINT, 4326) NOT NULL
);

CREATE INDEX admin_regions_geom_idx     ON admin_regions USING GIST (geom);
CREATE INDEX admin_regions_centroid_idx ON admin_regions USING GIST (geom_centroid);
CREATE INDEX admin_regions_level_idx    ON admin_regions (level);
CREATE INDEX admin_regions_parent_idx   ON admin_regions (parent_id);

-- Polygon seed lands in Session 2 (dataset acquisition + hand-curation of DKI 5 kotamadya +
-- Kepulauan Seribu + 12nm maritime buffer). Until then admin_regions is empty: the trigger's
-- 4-step ladder short-circuits at every step and posts.city_name / city_match_type stay NULL,
-- which all three timelines tolerate (NULL renders as "" in the JSON response).

-- BEFORE INSERT trigger on posts: implements the 4-step reverse-geocoding fallback ladder
-- from docs/02-Product.md:192-196. Reads NEW.actual_location (sanctioned DB-side read per
-- the coordinate-jitter capability) and populates the pre-existing posts.city_name +
-- posts.city_match_type columns. Never surfaces actual_location to any client; only the
-- resolved city_name string is projected into user-facing JSON.
CREATE OR REPLACE FUNCTION posts_set_city_fn() RETURNS TRIGGER AS $$
DECLARE
    matched_name TEXT;
BEGIN
    -- Caller override: if city_name was explicitly supplied (bulk import / backfill), skip.
    IF NEW.city_name IS NOT NULL THEN
        RETURN NEW;
    END IF;

    -- Step 1: strict ST_Contains match.
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_Contains(geom::geometry, NEW.actual_location::geometry)
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'strict';
        RETURN NEW;
    END IF;

    -- Step 2: 10-meter buffered match + deterministic centroid tie-breaker.
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_DWithin(geom, NEW.actual_location, 10)
     ORDER BY ST_Distance(geom_centroid, NEW.actual_location) ASC
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'buffered_10m';
        RETURN NEW;
    END IF;

    -- Step 3: nearest kabupaten/kota within 50 km (catches coastal + EEZ-adjacent posts).
    SELECT name INTO matched_name
      FROM admin_regions
     WHERE level = 'kabupaten_kota'
       AND ST_DWithin(geom, NEW.actual_location, 50000)
     ORDER BY ST_Distance(geom, NEW.actual_location) ASC
     LIMIT 1;
    IF matched_name IS NOT NULL THEN
        NEW.city_name := matched_name;
        NEW.city_match_type := 'fuzzy_match';
        RETURN NEW;
    END IF;

    -- Step 4: out of range. Both columns stay NULL.
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER posts_set_city_tg
    BEFORE INSERT ON posts
    FOR EACH ROW EXECUTE FUNCTION posts_set_city_fn();

-- Idempotent no-op refresh: V4 already defined this view and SELECT * always projected
-- city_name + city_match_type. Re-issuing the DDL here anchors the view definition at V11
-- in pg_views.definition for downstream audit.
CREATE OR REPLACE VIEW visible_posts AS
    SELECT * FROM posts WHERE is_auto_hidden = FALSE;
