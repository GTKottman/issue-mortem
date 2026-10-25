#!/usr/bin/env bash
# Builds "Issue Mortem.apk" with the plain Android SDK tools (no Gradle).
# Needs ANDROID_SDK (build-tools 35.0.0 + platform android-35) and JDK 17. Signs with the debug key unless
# KEYSTORE / KEYSTORE_PASS / KEY_ALIAS are set.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?set ANDROID_SDK to your Android SDK folder}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/base.apk" "$OUT/res.zip" \
  --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 0.0.1
"$JAVA_HOME/bin/javac" -source 17 -target 17 -Xlint:-options -cp "$JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 29 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/debug.keystore}" --ks-pass "pass:${KEYSTORE_PASS:-android}" \
  ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} --out "Issue Mortem.apk" "$OUT/aligned.apk"
echo "built: $(pwd)/Issue Mortem.apk"
