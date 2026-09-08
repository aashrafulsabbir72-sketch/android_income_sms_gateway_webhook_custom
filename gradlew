#!/bin/bash
GRADLE_HOME="$(cd "$(dirname "$0")/gradle/wrapper/gradle-7.4.2" && pwd)"
exec "$GRADLE_HOME/bin/gradle" "$@"
