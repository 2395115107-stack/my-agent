#!/usr/bin/env bash
# my-agent 本地环境停止
set -e
TOOLS="${TOOLS_DIR:-D:/Users/agent/tools}"
"$TOOLS/pgsql/bin/pg_ctl.exe" -D "$TOOLS/pgdata" stop -m fast || true
"$TOOLS/redis/redis-cli.exe" -p 6380 shutdown nosave || true
echo "postgres:5433 / redis:6380 stopped"
