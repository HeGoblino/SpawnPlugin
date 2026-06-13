#!/bin/sh
# Downloads Maven automatically on first run, then builds the project.

MVN_VERSION="3.9.6"
MVN_DIR="$HOME/.mvn-wrapper/apache-maven-$MVN_VERSION"
MVN_URL="https://archive.apache.org/dist/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz"

if [ ! -d "$MVN_DIR" ]; then
    echo "Downloading Apache Maven $MVN_VERSION (first time only, ~10MB)..."
    mkdir -p "$HOME/.mvn-wrapper"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$MVN_URL" | tar -xzC "$HOME/.mvn-wrapper/"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO- "$MVN_URL" | tar -xzC "$HOME/.mvn-wrapper/"
    else
        echo "ERROR: curl or wget not found. Please install one and try again."
        exit 1
    fi
    echo "Maven downloaded successfully!"
fi

exec "$MVN_DIR/bin/mvn" "$@"
