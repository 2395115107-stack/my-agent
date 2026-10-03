#!/usr/bin/env bash
# my-agent 本地环境启动(便携版,无需 Docker / 管理员权限)
# 用法: bash scripts/env-start.sh
set -e
TOOLS="${TOOLS_DIR:-D:/Users/agent/tools}"

# 1) Redis 5(6380:本机 6379 已有老版 Redis 3.0.504,Redisson 4.3.1 兼容性差,故用自带实例)
if ! "$TOOLS/redis/redis-cli.exe" -p 6380 ping >/dev/null 2>&1; then
  mkdir -p "$TOOLS/redis-data"
  (cd "$TOOLS/redis" && ./redis-server.exe --port 6380 --dir "$TOOLS/redis-data" --save 60 1 --daemonize no > "$TOOLS/redis6380.log" 2>&1 &)
  sleep 2
fi
"$TOOLS/redis/redis-cli.exe" -p 6380 ping && echo "redis:6380 OK"

# 2) PostgreSQL 16.4(5433:避开本机已有的 5432 实例)
if ! "$TOOLS/pgsql/bin/pg_ctl.exe" -D "$TOOLS/pgdata" status >/dev/null 2>&1; then
  "$TOOLS/pgsql/bin/pg_ctl.exe" -D "$TOOLS/pgdata" -o "-p 5433" -l "$TOOLS/pg.log" start
  sleep 2
fi
echo "postgres:5433 OK (db=myagent user=postgres trust)"
