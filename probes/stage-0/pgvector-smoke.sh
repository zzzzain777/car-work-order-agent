#!/usr/bin/env bash
set -euo pipefail

probe_container="agent-pgvector-probe-$$"
probe_image="pgvector/pgvector:0.8.7-pg17-bookworm"

cleanup() {
  docker stop "$probe_container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# No published port or external network. The database and trust auth exist only
# inside this short-lived container; no real credentials or customer data are used.
docker run --rm -d --network none --name "$probe_container" \
  -e POSTGRES_HOST_AUTH_METHOD=trust "$probe_image" >/dev/null

ready=false
for attempt in {1..30}; do
  # pg_isready can briefly succeed against the initialization server, which
  # shuts down before the long-running server starts.
  if [[ "$(docker logs "$probe_container" 2>&1)" == *'PostgreSQL init process complete'* ]] \
      && docker exec "$probe_container" pg_isready -U postgres >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "PostgreSQL did not become ready" >&2
  exit 1
fi

docker exec -i "$probe_container" psql -U postgres -v ON_ERROR_STOP=1 -At <<'SQL'
CREATE EXTENSION vector;
SELECT 'postgres=' || current_setting('server_version') || ',pgvector=' || extversion
  FROM pg_extension WHERE extname = 'vector';
CREATE TEMP TABLE probe (id integer PRIMARY KEY, embedding vector(1024));
INSERT INTO probe VALUES
  (1, array_fill(0.0::real, ARRAY[1024])::vector),
  (2, (ARRAY[1.0::real] || array_fill(0.0::real, ARRAY[1023]))::vector);
SELECT 'rows=' || count(*) || ',dims=' || min(vector_dims(embedding)) || '..' || max(vector_dims(embedding))
  FROM probe;
SELECT 'nearest=' || id
  FROM probe
  ORDER BY embedding <-> (ARRAY[1.0::real] || array_fill(0.0::real, ARRAY[1023]))::vector
  LIMIT 1;
DO $$
BEGIN
  IF (SELECT id FROM probe
      ORDER BY embedding <-> (ARRAY[1.0::real] || array_fill(0.0::real, ARRAY[1023]))::vector
      LIMIT 1) <> 2 THEN
    RAISE EXCEPTION 'nearest neighbor mismatch';
  END IF;
END
$$;
SQL

if wrong_dimensions="$(docker exec -i "$probe_container" psql -U postgres -v ON_ERROR_STOP=1 -At 2>&1 <<'SQL'
CREATE TEMP TABLE dimension_guard (embedding vector(1024));
INSERT INTO dimension_guard VALUES ('[1,2,3]');
SQL
)"; then
  echo "Three-dimensional vector was unexpectedly accepted" >&2
  exit 1
fi
if [[ "$wrong_dimensions" != *"expected 1024 dimensions, not 3"* ]]; then
  echo "$wrong_dimensions" >&2
  exit 1
fi
echo "wrong_dimensions=rejected"
