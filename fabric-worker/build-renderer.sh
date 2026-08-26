#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
worker_root="$project_root/fabric-worker"
build_root="$(mktemp -d "$worker_root/.build-tmp.XXXXXX")"
classes_root="$build_root/classes"
stage_root="$build_root/stage"
output_root="$worker_root/build/libs"
mkdir -p "$classes_root" "$stage_root" "$output_root"

source_list="$build_root/sources.txt"
find "$worker_root/src/client/java" "$worker_root/build-support" -name '*.java' -print > "$source_list"
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 --release 17 -d "$classes_root" "@$source_list"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 --release 17 -d "$classes_root" "@$source_list"
fi

worker_stage="$stage_root/studio/litematicrender/worker"
mkdir -p "$worker_stage"
for class_file in "$classes_root/studio/litematicrender/worker"/*.class; do
  class_name="$(basename "$class_file")"
  case "$class_name" in
    BridgeRenderSelfTest*) continue ;;
  esac
  cp "$class_file" "$worker_stage/$class_name"
done
cp "$worker_root/src/main/resources/fabric.mod.json" "$stage_root/fabric.mod.json"
cp "$worker_root/src/main/resources/lrs-renderer-capabilities.json" "$stage_root/lrs-renderer-capabilities.json"

self_test_root="$build_root/self-test"
java -cp "$classes_root" studio.litematicrender.worker.RendererSelfTest "$self_test_root"

bridge_test_root="$build_root/bridge-self-test"
java -cp "$classes_root" studio.litematicrender.worker.BridgeRenderSelfTest "$bridge_test_root"
rg -q '"type":"completed"' "$bridge_test_root/session/events.jsonl"
echo "WORKER_BRIDGE_RENDER_SELF_TEST_OK"

output="$output_root/litematic-render-worker-1.0.0-unified-entity-frame.jar"
if command -v jar >/dev/null 2>&1; then
  jar cf "$output" -C "$stage_root" .
else
  java -m jdk.jartool/sun.tools.jar.Main cf "$output" -C "$stage_root" .
fi

echo "$output"
