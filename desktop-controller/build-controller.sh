#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
controller_root="$project_root/desktop-controller"
build_root="$(mktemp -d "$controller_root/.build-tmp.XXXXXX")"
classes_root="$build_root/classes"
output_root="$controller_root/build"
mkdir -p "$classes_root/META-INF" "$classes_root/worker" "$output_root"

bash "$project_root/fabric-worker/build-renderer.sh"

if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 --release 8 -d "$classes_root" $(find "$controller_root/src/main/java" -name '*.java' -print)
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 --release 8 -d "$classes_root" $(find "$controller_root/src/main/java" -name '*.java' -print)
fi

cp -R "$controller_root/src/main/resources/." "$classes_root/"
cp "$project_root/fabric-worker/build/libs/litematic-render-worker-1.0.0-unified-entity-frame.jar" "$classes_root/worker/litematic-render-worker-1.0.0-unified-entity-frame.jar"

if command -v jar >/dev/null 2>&1; then
  jar cfm "$output_root/DsLR.jar" "$classes_root/META-INF/MANIFEST.MF" -C "$classes_root" .
else
  java -m jdk.jartool/sun.tools.jar.Main cfm "$output_root/DsLR.jar" "$classes_root/META-INF/MANIFEST.MF" -C "$classes_root" .
fi

java -Dlrs.dataRoot="$build_root/self-test-data" -jar "$output_root/DsLR.jar" --self-test
echo "$output_root/DsLR.jar"
