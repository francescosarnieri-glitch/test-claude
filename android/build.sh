#!/usr/bin/env bash
# Crea l'APK "Registro Cimiteriale" partendo da ../registro-cimiteriale.html
set -euo pipefail
: "${REGISTRO_KS_PASS:?Imposta REGISTRO_KS_PASS con la password della chiave di firma}"
# La chiave di firma (keystore/registro.jks) NON è nel repository: va conservata a parte.
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
source <(grep -E '^(versionCode|versionName)=' version.properties)
OUT=build; rm -rf "$OUT"; mkdir -p "$OUT"/{res,classes,apk/assets,gen}

# 1. Pagina dell'app = pagina del registro + collegamento Android
{ cat tools/head.html; cat ../registro-cimiteriale.html; printf '\n</body>\n</html>\n'; } > "$OUT/apk/assets/index.html"

# 2. Manifest con la versione
sed -e "s/@VERSION_CODE@/$versionCode/" -e "s/@VERSION_NAME@/$versionName/" AndroidManifest.xml > "$OUT/AndroidManifest.xml"

# 3. Risorse
"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$OUT/AndroidManifest.xml" \
  -A "$OUT/apk/assets" --java "$OUT/gen" "$OUT/res/res.zip" --min-sdk-version 24 --target-sdk-version 34

# 4. Codice Java -> dex
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -cp "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v '^warning\|^Note\|^1 warning\|source value 8\|target value 8\|To suppress' || true
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && cp base.apk unsigned.apk && zip -q -j unsigned.apk classes.dex)

# 5. Allineamento e firma (sempre la stessa chiave: serve per gli aggiornamenti)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks keystore/registro.jks --ks-pass env:REGISTRO_KS_PASS --ks-key-alias registro \
  --out "../RegistroCimiteriale-v$versionName.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "../RegistroCimiteriale-v$versionName.apk"
echo "Creato: RegistroCimiteriale-v$versionName.apk (versionCode $versionCode)"
