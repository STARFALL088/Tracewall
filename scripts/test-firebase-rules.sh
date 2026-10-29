#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec firebase emulators:exec --config firebase/firebase.json --project demo-tracewall \
  --only auth,database,firestore 'node firebase/test_rules.mjs'
