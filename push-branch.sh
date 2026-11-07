#!/usr/bin/env bash
# Push the "Update Helper" rename branch to GitHub so CI builds it.
# This repo is public, so no auth token is required at all:
#   git push https://github.com/pratiks360/UpdateHelper.git <branch>
#
# Run from this repo root (repo/) or point GIT_DIR at it.

set -euo pipefail

REPO="https://github.com/pratiks360/UpdateHelper.git"
BRANCH="freebuff/9709dca6c1abc2a7f50c4fe2/spoof-accessibility"

git push "$REPO" "$BRANCH"
