#!/usr/bin/env bash
# Adds the macOS arm64 native library to the jar of the "Build hedging jar" workflow.
#
# Use this script if GitHub had no macOS arm64 runner for the workflow. Run it on a Mac with Apple silicon, from the
# root of this repository, on the commit that the workflow built:
#
#   utils/add-macos-library.sh [RUN_ID]
#
# The script builds the macOS arm64 library, downloads the jar of the workflow run, adds the library to the jar, and
# makes sure that the jar can load it. If you give no RUN_ID, the script uses the latest workflow run for the current
# commit. The result is in target/hedging-jar/.
set -euo pipefail

repo=${REPO:-arouel/aws-crt-java}
workflow=build-hedging-jar.yml
version=$(awk '/CRT_VERSION:/ { print $2; exit }' ".github/workflows/$workflow")
lib_path=osx/armv8/cruntime/libaws-crt-jni.dylib
lib="target/cmake-build/lib/$lib_path"
out=target/hedging-jar

if [ "$(uname -s)-$(uname -m)" != "Darwin-arm64" ]; then
    echo "Run this script on a Mac with Apple silicon." >&2
    exit 1
fi

commit=$(git rev-parse HEAD)
if ! git diff --quiet HEAD -- src crt CMakeLists.txt pom.xml; then
    echo "Warning: the sources have local changes, so the macOS library can differ from the Linux libraries." >&2
fi

echo "Building the macOS arm64 library of $commit"
git submodule update --init
mvn -B -q package -DskipTests -P mac-arm64
if ! grep -q -a AWS_CRT_S3_GET_OBJECT_HEDGING "$lib"; then
    echo "$lib does not contain GET hedging. Check the crt/aws-c-s3 submodule." >&2
    exit 1
fi

run_id=${1:-}
if [ -z "$run_id" ]; then
    run_id=$(gh run list --repo "$repo" --workflow "$workflow" --commit "$commit" --limit 1 \
        --json databaseId --jq '.[0].databaseId // empty')
    if [ -z "$run_id" ]; then
        echo "No run of $workflow for $commit. Give the RUN_ID." >&2
        exit 1
    fi
fi

echo "Downloading aws-crt-$version from run $run_id"
rm -rf "$out"
mkdir -p "$out"
gh run download "$run_id" --repo "$repo" --name "aws-crt-$version" --dir "$out/artifact"
cp "$out/artifact/target/aws-crt-$version.jar" "$out/aws-crt-$version.jar"
cp "$out/artifact/pom.xml" "$out/pom.xml"
rm -rf "$out/artifact"

jar=$out/aws-crt-$version.jar
if unzip -l "$jar" | grep -q -F "$lib_path"; then
    echo "The jar already contains $lib_path. It is not changed."
else
    echo "Adding $lib_path"
    (cd target/cmake-build/lib && zip -q "$OLDPWD/$jar" "$lib_path")
fi
unzip -l "$jar" | grep -E 'libaws-crt-jni\.(so|dylib)$'

smoke_dir=$(mktemp -d)
trap 'rm -rf "$smoke_dir"' EXIT
cat > "$smoke_dir/Smoke.java" <<'EOF'
import software.amazon.awssdk.crt.CRT;

public class Smoke {
    public static void main(String[] args) {
        CRT.checkLoaded();
        System.out.println(CRT.getOSIdentifier() + "-" + CRT.getArchIdentifier() + ": " + CRT.awsErrorName(2092));
    }
}
EOF
java -cp "$jar" "$smoke_dir/Smoke.java"

echo "Done: $jar"
echo "To install it in the local Maven repository:"
echo "  mvn install:install-file -Dfile=$jar -DpomFile=$out/pom.xml"
