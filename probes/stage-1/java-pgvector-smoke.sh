#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd)"
probe_net="agent-rag-probe-$$"
probe_db="agent-rag-db-$$"
probe_password="$(openssl rand -hex 16)"

cleanup() {
  docker rm -f "$probe_db" >/dev/null 2>&1 || true
  docker network rm "$probe_net" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$probe_net" >/dev/null
docker run -d --rm --name "$probe_db" --network "$probe_net" \
  -e POSTGRES_USER=probe -e POSTGRES_DB=agent -e POSTGRES_PASSWORD="$probe_password" \
  pgvector/pgvector:0.8.7-pg17-bookworm >/dev/null

ready=false
for attempt in {1..30}; do
  if docker logs "$probe_db" 2>&1 | rg -q 'PostgreSQL init process complete' \
      && docker exec "$probe_db" pg_isready -U probe -d agent >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "临时 pgvector 数据库未能就绪" >&2
  exit 1
fi

docker run --rm --network "$probe_net" \
  -e AGENT_PGVECTOR_PROBE=true \
  -e AGENT_PGVECTOR_JDBC_URL="jdbc:postgresql://$probe_db:5432/agent" \
  -e AGENT_PGVECTOR_USER=probe \
  -e AGENT_PGVECTOR_PASSWORD="$probe_password" \
  -v "$project_root":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -q -Dtest=PolicyPgVectorProbeTest test
