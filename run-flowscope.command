#!/bin/zsh
set -e

SCRIPT_DIR=${0:A:h}
cd "$SCRIPT_DIR"
exec ./gradlew --no-daemon :recorder-ide:run --console=plain
