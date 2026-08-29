#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

echo "📥 Initializing git submodules..."
git submodule init && git submodule update

echo "🎨 Initializing web assets..."
pushd src/main/webui
yarn install && yarn yarn:frzinstall
popd

echo "📦 Staging artifacts..."
./mvnw --batch-mode --no-transfer-progress \
  -Ppublication,snapshots \
  -DskipTests=true \
  -Dspotless.check.skip=true \
  -Dspotbugs.skip=true \
  -Dquarkus.container-image.build=false \
  -Dquarkus.package.jar.type=uber-jar

echo "🚀 Releasing..."
./mvnw --batch-mode --no-transfer-progress \
  -Prelease,snapshots \
  jreleaser:deploy

echo "🎉 Done!"