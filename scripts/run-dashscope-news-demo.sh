#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_LOG="${TMPDIR:-/tmp}/spring-ai-vector-demo-dashscope.log"
APP_PID=""

if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
  echo "DASHSCOPE_API_KEY is required" >&2
  exit 1
fi

if [[ -z "${EASYSEARCH_PASSWORD:-}" ]]; then
  echo "EASYSEARCH_PASSWORD is required" >&2
  exit 1
fi

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "==> Starting spring-ai-vector-demo with dashscope profile"
(
  cd "$ROOT_DIR"
  mvn spring-boot:run -Dspring-boot.run.profiles=dashscope >"$APP_LOG" 2>&1
) &
APP_PID="$!"

echo "==> Waiting for application-internal VectorStore demo"
DEMO_READY=false
for _ in {1..60}; do
  if grep -q "REST 交互入口仍可使用" "$APP_LOG" 2>/dev/null; then
    DEMO_READY=true
    break
  fi
  sleep 1
done

if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
  echo "Demo failed to start. Last log lines:" >&2
  tail -80 "$APP_LOG" >&2 || true
  exit 1
fi

if [[ "$DEMO_READY" != "true" ]]; then
  echo "Demo did not finish the internal VectorStore search within 60 seconds. Last log lines:" >&2
  tail -80 "$APP_LOG" >&2 || true
  exit 1
fi

echo
echo "==> Application output from internal VectorStore.add(...) and VectorStore.similaritySearch(...) calls"
sed -n '/>> 新闻标题样例已通过 Spring AI VectorStore 写入 Easysearch 索引：/,$p' "$APP_LOG" \
  | sed -n '1,/REST 交互入口仍可使用/p'

echo
echo "Demo complete. Log: $APP_LOG"
