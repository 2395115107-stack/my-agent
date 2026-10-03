#!/usr/bin/env bash
# 构建并启动 my-agent(8070)
# 模型配置:export LLM_BASE_URL / LLM_API_KEY / LLM_MODEL 后再运行
set -e
cd "$(dirname "$0")/.."
MVN="${MVN:-D:/Users/agent/tools/apache-maven-3.9.9/bin/mvn}"
SETTINGS="${SETTINGS:-D:/Users/agent/tools/settings.xml}"

export PG_HOST=127.0.0.1 PG_PORT=5433 PG_DB=myagent PG_USER=postgres PG_PASSWORD=postgres
export REDIS_HOST=127.0.0.1 REDIS_PORT=6380

"$MVN" -s "$SETTINGS" -B -q -DskipTests clean package
exec java -jar target/my-agent.jar
