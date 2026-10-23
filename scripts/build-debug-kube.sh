#!/usr/bin/env bash
set -euo pipefail
umask 077

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
context=tbs-dev
namespace=omp-mobile-build
signing_namespace=omp-mobile-signing
tmp=$(mktemp -d)
own_namespace=0

cleanup() {
    rm -rf "$tmp"
    if [[ $own_namespace == 1 && ${OMP_MOBILE_KEEP_BUILD_POD:-0} != 1 ]]; then
        kubectl --context "$context" delete namespace "$namespace" --wait=true --timeout=180s
    fi
}
trap cleanup EXIT

# This namespace and its immutable secret outlive disposable build pods.
# Never generate a replacement key here: it would break installed-app updates.
kubectl --context "$context" -n "$signing_namespace" get secret debug-keystore \
    -o 'jsonpath={.data.debug\.keystore}' | base64 --decode > "$tmp/debug.keystore"
test -s "$tmp/debug.keystore"
backup="${XDG_CONFIG_HOME:-$HOME/.config}/omp-mobile/signing/debug.keystore"
if [[ -f "$backup" ]] && ! cmp -s "$backup" "$tmp/debug.keystore"; then
    echo 'Local backup and canonical signing secret differ; refusing to rotate the signing key.' >&2
    exit 1
fi
if kubectl --context "$context" get namespace "$namespace" >/dev/null 2>&1; then
    echo "$namespace already exists; refusing to overwrite another build." >&2
    exit 1
fi
kubectl --context "$context" create namespace "$namespace"
own_namespace=1
kubectl --context "$context" -n "$namespace" create secret generic debug-keystore \
    --from-file="debug.keystore=$tmp/debug.keystore"
kubectl --context "$context" apply -f - <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: android-builder
  namespace: omp-mobile-build
spec:
  restartPolicy: Never
  containers:
    - name: builder
      image: eclipse-temurin:17-jdk
      command: ["sleep", "infinity"]
      env:
        - {name: ANDROID_HOME, value: /opt/android}
        - {name: ANDROID_SDK_ROOT, value: /opt/android}
        - {name: GRADLE_USER_HOME, value: /workspace/.gradle}
        - {name: OMP_MOBILE_DEBUG_KEYSTORE, value: /signing/debug.keystore}
      resources:
        requests: {cpu: "2", memory: 4Gi}
        limits: {cpu: "8", memory: 12Gi}
      volumeMounts:
        - {name: workspace, mountPath: /workspace}
        - {name: signing, mountPath: /signing, readOnly: true}
  volumes:
    - name: workspace
      emptyDir: {}
    - name: signing
      secret:
        secretName: debug-keystore
        defaultMode: 0400
YAML
kubectl --context "$context" -n "$namespace" wait --for=condition=Ready pod/android-builder --timeout=180s
kubectl --context "$context" -n "$namespace" exec -i android-builder -- bash -se <<'SETUP'
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip curl ca-certificates >/dev/null
mkdir -p /opt/android/cmdline-tools
curl -fsSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q /tmp/cmdline.zip -d /opt/android/cmdline-tools
mv /opt/android/cmdline-tools/cmdline-tools /opt/android/cmdline-tools/latest
yes | /opt/android/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
/opt/android/cmdline-tools/latest/bin/sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' platform-tools
SETUP

tar -czf "$tmp/src.tgz" --exclude=.git --exclude=.omp --exclude=.serena \
    --exclude=.gradle --exclude=build --exclude='*/build' --exclude=dist --exclude=evidence \
    --exclude='*.keystore' --exclude='*.jks' -C "$root" .
kubectl --context "$context" -n "$namespace" cp "$tmp/src.tgz" android-builder:/workspace/src.tgz
kubectl --context "$context" -n "$namespace" exec -i android-builder -- bash -se <<'BUILD'
tar -xzf /workspace/src.tgz -C /workspace
cd /workspace
./gradlew :app:assembleDebug :collab:testDebugUnitTest --no-daemon --console=plain \
    -Dorg.gradle.jvmargs='-Xmx6g -Dfile.encoding=UTF-8'
apk=app/build/outputs/apk/debug/app-debug.apk
/opt/android/build-tools/37.0.0/apksigner verify --print-certs "$apk" | tee /workspace/apk-signature.txt
expected=$(tr -d '\r\n' < signing/debug-certificate.sha256)
actual=$(awk '/certificate SHA-256 digest:/{print $NF}' /workspace/apk-signature.txt | sort -u)
test "$actual" = "$expected"
/opt/android/build-tools/37.0.0/aapt2 dump badging "$apk" | grep -F "package: name='dev.omp.mobile.debug'"
BUILD

kubectl --context "$context" -n "$namespace" cp \
    android-builder:/workspace/app/build/outputs/apk/debug/app-debug.apk \
    "$root/dist/omp-mobile-0.1.0-debug.apk"
(cd "$root/dist" && sha256sum omp-mobile-0.1.0-debug.apk > omp-mobile-0.1.0-debug.apk.sha256)
cat "$root/dist/omp-mobile-0.1.0-debug.apk.sha256"
