#!/usr/bin/env bash
# 构建交付包:dist/my-agent-<version>.zip
# 内容:可执行 jar、交付说明、建表脚本、.env 样例、启停脚本
set -e
cd "$(dirname "$0")/.."
VERSION=$(ls target/my-agent.jar >/dev/null 2>&1 && echo 0.1.0 || echo 0.1.0)
OUT="dist/my-agent-${VERSION}"

rm -rf "$OUT" && mkdir -p "$OUT/scripts"
cp target/my-agent.jar "$OUT/"
cp README-DELIVERY.md "$OUT/"
cp .env.example "$OUT/"
cp sql/schema.sql "$OUT/sql_schema.sql" 2>/dev/null || cp src/main/resources/schema.sql "$OUT/sql_schema.sql"

cat > "$OUT/scripts/start.sh" <<'EOF'
#!/usr/bin/env bash
# 加载 .env 并启动(交付包内使用)
cd "$(dirname "$0")/.."
[ -f .env ] && export $(grep -v '^#' .env | xargs)
exec java -jar my-agent.jar
EOF
cat > "$OUT/scripts/stop.sh" <<'EOF'
#!/usr/bin/env bash
# 停止本机 my-agent(按 jar 名匹配)
powershell -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*my-agent.jar*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
EOF
chmod +x "$OUT/scripts/"*.sh

(cd "$OUT" && tar -a -c -f "../my-agent-${VERSION}.zip" .)
echo "dist/my-agent-${VERSION}.zip 打包完成:"
tar -tf "dist/my-agent-${VERSION}.zip"
