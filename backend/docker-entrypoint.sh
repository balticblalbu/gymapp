#!/bin/sh
set -e

# Applies pending migrations and seeds the catalogue before the API starts.
# Both steps are idempotent, so a restart is harmless.

echo "▶ Datenbank-Migrationen werden angewendet …"
npx prisma migrate deploy

if [ "${SKIP_SEED}" != "true" ]; then
  echo "▶ Seed wird ausgeführt …"
  node dist/prisma/seed.js || echo "⚠ Seed übersprungen (bereits vorhanden?)"
fi

echo "▶ Server startet …"
exec node dist/src/index.js
