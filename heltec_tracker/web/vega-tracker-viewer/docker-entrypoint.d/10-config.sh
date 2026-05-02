#!/bin/sh
# Substitute API_BASE into /usr/share/nginx/html/config.js at container start
# so the same image can point at different backends without a rebuild.
set -eu

CONFIG_JS=/usr/share/nginx/html/config.js
API_BASE_VALUE="${API_BASE:-http://192.168.86.48:8030}"

# Escape ampersand and slashes for sed.
ESC=$(printf '%s' "$API_BASE_VALUE" | sed -e 's/[\/&]/\\&/g')

if [ -f "$CONFIG_JS" ]; then
    sed -i "s|__API_BASE__|${ESC}|g" "$CONFIG_JS"
    echo "[entrypoint] config.js patched with API_BASE=${API_BASE_VALUE}"
fi
