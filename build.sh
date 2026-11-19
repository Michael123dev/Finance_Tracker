#!/bin/bash
# Builds a signed release APK without Gradle:
#   aapt (R.java) -> kotlinc -> ProGuard (shrink stdlib) -> dx -> aapt (package) -> zipalign -> apksigner
# Requirements: JDK 17+, kotlinc 2.x, aapt, dalvik-exchange (dx), proguard, zipalign, apksigner,
#               an android.jar for API 34 (path in ANDROID_JAR).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)
export JAVA_TOOL_OPTIONS= _JAVA_OPTIONS=
AJ=${ANDROID_JAR:-/home/claude/tools/android-34.jar}
KOTLINC=${KOTLINC:-/opt/kotlinc/bin/kotlinc}
STDLIB=${KOTLIN_STDLIB:-/opt/kotlinc/lib/kotlin-stdlib.jar}
OUT=$ROOT/build
VERSION_NAME=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"
quiet_aapt() { grep -vE 'ResourceType|E aapt|^$' || true; }

echo "[1/7] Generating R.java"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$AJ" 2>&1 | quiet_aapt

echo "[2/7] Compiling Kotlin"
"$KOTLINC" src "$OUT/gen" -d "$OUT/classes" -cp "$AJ" -no-reflect -no-jdk -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -nowarn 2>&1 | tee "$OUT/kotlinc.log"
if grep -q 'error:' "$OUT/kotlinc.log"; then echo "Kotlin compile failed"; exit 1; fi

echo "[3/7] Shrinking with ProGuard"
cat > "$OUT/proguard.pro" <<EOF
-injars $OUT/classes
-injars $STDLIB(!META-INF/**)
-outjars $OUT/min.jar
-libraryjars $AJ
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-dontwarn **
-dontnote **
-dontoptimize
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
EOF
proguard @"$OUT/proguard.pro" > "$OUT/proguard.log" 2>&1 || { tail -30 "$OUT/proguard.log"; exit 1; }

echo "[4/7] Dexing"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/min.jar"
if dexdump -d "$OUT/classes.dex" 2>/dev/null | grep -q invoke-custom; then echo "invoke-custom found (not supported)"; exit 1; fi

echo "[5/7] Packaging resources"
aapt package -f -M AndroidManifest.xml -S res -I "$AJ" -F "$OUT/unsigned.apk" 2>&1 | quiet_aapt
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)

echo "[6/7] Aligning"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] Signing"
PASS=$(grep storePassword keystore/keystore.properties | cut -d= -f2)
apksigner sign --ks keystore/release.jks --ks-key-alias fintrack --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --min-sdk-version 26 --out "$OUT/FinanceTracker-$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/FinanceTracker-$VERSION_NAME.apk"
ls -la "$OUT/FinanceTracker-$VERSION_NAME.apk"
