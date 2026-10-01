#!/bin/zsh
# Starts Minecraft 1.21.1 with the MHW bridge mod (Fabric dev launch, offline player "Steve").
# If MHW is running, Minecraft drops straight into the "MHW Bridge" world and overlays MHW.
# Needs two JDKs (brew install --cask temurin@21 temurin@25): Gradle and Fabric Loom 1.18 run on
# Java 25, and Minecraft 1.21.1 compiles and runs on Java 21, which Gradle finds by itself.
# GRADLE_JAVA_HOME overrides the JDK Gradle runs on (an inherited JAVA_HOME is ignored: it's often 17 or 21).
set -euo pipefail
ROOT=${0:A:h:h}
JAVA_HOME=${GRADLE_JAVA_HOME:-}
if [[ -z "$JAVA_HOME" ]]; then
  JAVA_HOME=$(/usr/libexec/java_home -F -v 25 2>/dev/null || /usr/libexec/java_home -F -v 25+ 2>/dev/null) || {
    echo "No JDK 25 found (Gradle needs it): brew install --cask temurin@25" >&2
    exit 1
  }
fi
/usr/libexec/java_home -F -v 21 >/dev/null 2>&1 || {
  echo "No JDK 21 found (Minecraft 1.21.1 needs it): brew install --cask temurin@21" >&2
  exit 1
}
export JAVA_HOME
cd "$ROOT/mc-bridge"
exec ./gradlew --no-configuration-cache runClient "$@"
