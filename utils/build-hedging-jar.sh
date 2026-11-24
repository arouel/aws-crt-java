#!/usr/bin/env bash
# Builds the aws-crt jar with GET hedging for Linux x86_64, Linux aarch64, and macOS arm64 on a Mac.
#
# The "Build hedging jar" workflow builds only the Linux libraries, because GitHub gave it no macOS arm64 runner. This
# script downloads the Linux libraries of a workflow run, builds the macOS arm64 library on this Mac, and packs all
# three into one jar. The Linux libraries are available when the Linux jobs are complete.
#
# Run it on a Mac with Apple silicon, from the root of this repository, on the commit that the workflow built:
#
#   utils/build-hedging-jar.sh [RUN_ID]
#
# If you give no RUN_ID, the script uses the latest workflow run for the current commit. The result is in
# target/hedging-jar/: the jar, its sources jar, and its pom.xml.
set -euo pipefail

repo=${REPO:-arouel/aws-crt-java}
workflow=build-hedging-jar.yml
version=$(awk '/CRT_VERSION:/ { print $2; exit }' ".github/workflows/$workflow")
lib_dir=target/cmake-build/lib
out=target/hedging-jar

if [ "$(uname -s)-$(uname -m)" != "Darwin-arm64" ]; then
    echo "Run this script on a Mac with Apple silicon." >&2
    exit 1
fi

commit=$(git rev-parse HEAD)
if ! git diff --quiet HEAD -- src crt CMakeLists.txt pom.xml; then
    echo "Warning: the sources have local changes, so the macOS library can differ from the Linux libraries." >&2
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

download_dir=$(mktemp -d)
trap 'rm -rf "$download_dir"' EXIT

echo "Downloading the Linux libraries of run $run_id"
for classifier in linux-x86_64 linux-aarch_64; do
    gh run download "$run_id" --repo "$repo" --name "native-$classifier" --dir "$download_dir/$classifier"
done

# Remove the libraries of earlier runs, so that the jar gets only the libraries of this run.
rm -rf "$lib_dir/linux"
mkdir -p "$lib_dir"
for classifier in linux-x86_64 linux-aarch_64; do
    cp -R "$download_dir/$classifier/." "$lib_dir/"
done

# Set the version only for this build. The trap puts pom.xml back.
mvn -B -q versions:set -DnewVersion="$version" -DgenerateBackupPoms=true
trap 'mvn -B -q versions:revert; rm -rf "$download_dir"' EXIT

echo "Building the macOS arm64 library, the jar, and the sources jar of $commit"
git submodule update --init
mvn -B -q package org.apache.maven.plugins:maven-source-plugin:3.4.0:jar-no-fork -DskipTests -P mac-arm64

jar=target/aws-crt-$version.jar
# Read the listing from a variable. With pipefail, "unzip | grep -q" can fail when grep stops reading early.
listing=$(unzip -l "$jar")
for lib in linux/x86_64/glibc/libaws-crt-jni.so linux/armv8/glibc/libaws-crt-jni.so \
    osx/armv8/cruntime/libaws-crt-jni.dylib; do
    if ! grep -q -a AWS_CRT_S3_GET_OBJECT_HEDGING "$lib_dir/$lib"; then
        echo "$lib_dir/$lib does not contain GET hedging." >&2
        exit 1
    fi
    if ! grep -q -F "$lib" <<< "$listing"; then
        echo "The jar does not contain $lib." >&2
        exit 1
    fi
done
grep -E 'libaws-crt-jni\.(so|dylib)$' <<< "$listing"

cat > "$download_dir/Smoke.java" <<'EOF'
import software.amazon.awssdk.crt.CRT;

public class Smoke {
    public static void main(String[] args) {
        CRT.checkLoaded();
        System.out.println(CRT.getOSIdentifier() + "-" + CRT.getArchIdentifier() + ": " + CRT.awsErrorName(2092));
    }
}
EOF
java -cp "$jar" "$download_dir/Smoke.java"

sources_jar=target/aws-crt-$version-sources.jar
options_source=$(unzip -p "$sources_jar" software/amazon/awssdk/crt/s3/S3ClientOptions.java)
if ! grep -q withGetObjectHedging <<< "$options_source"; then
    echo "$sources_jar does not contain the sources of GET hedging." >&2
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"
cp "$jar" "$sources_jar" "$out/"
cp pom.xml "$out/pom.xml"

echo "Done: $out/aws-crt-$version.jar and $out/aws-crt-$version-sources.jar"
echo "To install them in the local Maven repository:"
echo "  mvn install:install-file -Dfile=$out/aws-crt-$version.jar -Dsources=$out/aws-crt-$version-sources.jar -DpomFile=$out/pom.xml"
