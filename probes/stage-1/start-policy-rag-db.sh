#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd)"
db_name="car-agent-pgvector-dev"
private_env="$project_root/config/policy-rag-db.env"
private_yml="$project_root/config/application-policy-rag.yml"
umask 077

if docker container inspect "$db_name" >/dev/null 2>&1; then
  if [[ ! -f "$private_env" || ! -f "$private_yml" ]]; then
    echo "数据库容器已存在，但本地私有配置缺失；请先核对容器和配置。" >&2
    exit 1
  fi
  docker start "$db_name" >/dev/null
else
  db_password="$(openssl rand -hex 24)"
  printf 'POSTGRES_USER=agent\nPOSTGRES_DB=agent\nPOSTGRES_PASSWORD=%s\n' "$db_password" > "$private_env"
  docker run -d --name "$db_name" --env-file "$private_env" \
    -p 127.0.0.1::5432 pgvector/pgvector:0.8.7-pg17-bookworm >/dev/null
  db_port="$(docker port "$db_name" 5432/tcp | awk -F: '{print $NF}' | head -1)"
  printf 'agent:\n  rag:\n    jdbc-url: jdbc:postgresql://127.0.0.1:%s/agent?socketFactory=com.zz.agent.policy.LocalPostgresSocketFactory\n    jdbc-user: agent\n    jdbc-password: %s\n' \
    "$db_port" "$db_password" > "$private_yml"
fi

ready=false
for attempt in {1..30}; do
  if docker exec "$db_name" pg_isready -U agent -d agent >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "本地 pgvector 数据库未在 30 秒内就绪。" >&2
  exit 1
fi
echo "本地 pgvector 数据库已就绪；连接信息仅在 Git 忽略的本地配置中。"
