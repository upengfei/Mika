#!/usr/bin/env bash
set -euo pipefail

# Keep downloaded dependencies between attempts; never retry a source/test failure.
attempt_log=$(mktemp)
trap 'rm -f "$attempt_log"' EXIT
for attempt in 1 2 3; do
    if ./gradlew --no-daemon --max-workers=2 "$@" 2>&1 | tee "$attempt_log"; then
        exit 0
    else
        result=${PIPESTATUS[0]}
    fi
    if [ "$attempt" -eq 3 ] || ! grep -Eq 'HTTP response code: 50[234]|[Ss]tatus code 50[234]|Could not (GET|HEAD)|Read timed out|Connection timed out|Temporary failure in name resolution' "$attempt_log"; then
        exit "$result"
    fi
    echo "Dependency network failure; retry $attempt of 2 in $((attempt * 10)) seconds."
    sleep "$((attempt * 10))"
done
