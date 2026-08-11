#!/usr/bin/env bash
set -euo pipefail

dms_checkout="${1:-dms}"
fixture_path="src/dms/backend/Fixtures/document-cache/materialized-documents"

if [ -z "${DMS_FIXTURE_REF:-}" ]; then
  echo "::error::DMS_FIXTURE_REF must be set to the DMS commit used for shared materialized-document fixtures."
  exit 1
fi

if ! git -C "$dms_checkout" rev-parse --git-dir > /dev/null 2>&1; then
  echo "::error::DMS checkout was not found at $dms_checkout."
  exit 1
fi

# DMS_FIXTURE_REF is intentionally a fixed Data-Management-Service commit, not main,
# so connector release builds remain reproducible. The validation below makes that pin
# an explicit release contract: it must be the checked-out DMS commit and it must be
# reachable from DMS main. Fixture drift from newer DMS main commits is diagnostic only.
git -C "$dms_checkout" fetch --no-tags origin main:refs/remotes/origin/main

expected_ref=$(git -C "$dms_checkout" rev-parse "${DMS_FIXTURE_REF}^{commit}")
actual_ref=$(git -C "$dms_checkout" rev-parse HEAD)

if [ "$actual_ref" != "$expected_ref" ]; then
  echo "::error::DMS checkout is at $actual_ref, but DMS_FIXTURE_REF resolves to $expected_ref."
  exit 1
fi

if ! git -C "$dms_checkout" merge-base --is-ancestor "$actual_ref" origin/main; then
  echo "::error::DMS_FIXTURE_REF=$actual_ref is not reachable from Data-Management-Service main."
  echo "::error::Update DMS_FIXTURE_REF to a main-reachable DMS commit."
  exit 1
fi

# actual_tree and main_tree are Git tree object IDs for only the shared fixture directory.
# Comparing those tree IDs catches fixture additions, removals, renames, or byte changes
# on DMS main without failing when unrelated DMS files change after the pinned commit.
actual_tree=$(git -C "$dms_checkout" rev-parse "HEAD:$fixture_path")
main_tree=$(git -C "$dms_checkout" rev-parse "origin/main:$fixture_path")

if [ "$actual_tree" != "$main_tree" ]; then
  echo "::warning::DMS_FIXTURE_REF is stale for $fixture_path."
  echo "::warning::Connector builds continue to use pinned fixtures from $actual_ref; review whether to bump DMS_FIXTURE_REF."
  echo "::warning::Current Data-Management-Service main is $(git -C "$dms_checkout" rev-parse origin/main)."
  git -C "$dms_checkout" diff --name-status HEAD origin/main -- "$fixture_path" || true
  echo "OK: DMS fixture pin $actual_ref is main-reachable; fixture-tree drift was reported as warning-only."
  exit 0
fi

echo "OK: DMS fixture pin $actual_ref is main-reachable and matches the current fixture tree."
