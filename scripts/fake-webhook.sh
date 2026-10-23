#!/usr/bin/env bash
# Shopify 가 보내는 것과 같은 서명(X-Shopify-Hmac-SHA256 = base64(HMAC-SHA256(body, client secret)))으로
# 가짜 웹훅을 라우터 릴레이에 보낸다. Shopify CLI 없이 릴레이 검증, 폴백, 전달을 확인할 때 쓴다.
#
# 사용: scripts/fake-webhook.sh <topic> [service] [region] [router]
#   topic   app/uninstalled | customers/data_request | customers/redact | shop/redact
#   service 비우면 쿼리 없이 보낸다(compliance 3종은 라우터가 접수 담당으로 폴백, 그 밖은 400)
#   router  기본 http://localhost:8080. ngrok 호스트를 주면 외부 경로로 보낸다
# secret 은 프로젝트 루트 .env 의 SHOPIFY_API_SECRET, 없으면 환경변수 SHOPIFY_API_SECRET 을 쓴다.
set -euo pipefail
TOPIC="${1:?topic 이 필요하다}"; SERVICE="${2:-}"; REGION="${3:-}"; ROUTER="${4:-http://localhost:8080}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SECRET="${SHOPIFY_API_SECRET:-$(grep '^SHOPIFY_API_SECRET=' "$DIR/.env" 2>/dev/null | cut -d= -f2- || true)}"
[ -n "$SECRET" ] || { echo "SHOPIFY_API_SECRET 이 없다(.env 또는 환경변수)"; exit 1; }
SHOP="${SHOP:-test-ceip-store.myshopify.com}"
PATH_TOPIC="${TOPIC/\//-}"            # customers/data_request → customers-data_request
case "$TOPIC" in
  app/uninstalled)        BODY="{\"id\":1,\"name\":\"stub\",\"myshopify_domain\":\"$SHOP\"}" ;;
  customers/data_request) BODY="{\"shop_id\":1,\"shop_domain\":\"$SHOP\",\"orders_requested\":[1001],\"customer\":{\"id\":7,\"email\":\"c@example.com\"},\"data_request\":{\"id\":9}}" ;;
  customers/redact)       BODY="{\"shop_id\":1,\"shop_domain\":\"$SHOP\",\"customer\":{\"id\":7,\"email\":\"c@example.com\"},\"orders_to_redact\":[1001]}" ;;
  shop/redact)            BODY="{\"shop_id\":1,\"shop_domain\":\"$SHOP\"}" ;;
  *) echo "모르는 topic: $TOPIC"; exit 1 ;;
esac
HMAC=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)
QUERY=""; [ -n "$SERVICE" ] && QUERY="?service=$SERVICE${REGION:+&region=$REGION}"
URL="$ROUTER/webhooks/shopify/default/$PATH_TOPIC$QUERY"
echo "POST $URL"
curl -sS -w '\n[%{http_code}]\n' -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "X-Shopify-Hmac-SHA256: $HMAC" \
  -H "X-Shopify-Topic: $TOPIC" \
  -H "X-Shopify-Shop-Domain: $SHOP" \
  -H "X-Shopify-Webhook-Id: fake-$(date +%s)-$RANDOM" \
  -H "X-Shopify-API-Version: 2026-07" \
  --data-binary "$BODY"
