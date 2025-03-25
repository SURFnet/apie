#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2024, 2025 SURF B.V.
# SPDX-License-Identifier: EPL-2.0 WITH Classpath-exception-2.0
# SPDX-FileContributor: Joost Diepenmaat
# SPDX-FileContributor: Remco van 't Veer

# Install clojure and babashka to `./bin` and `./lib`

set -ex

CLOJURE_VERSION="1.11.1.1273"
BABASHKA_VERSION="1.3.190"
BABASHKA_ARCH="linux-amd64"

if [ ! -x "bin/clojure" ]; then
    curl -O "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh"
    bash "linux-install-${CLOJURE_VERSION}.sh" -p "$(pwd)"
fi

if [ ! -x "bin/bb" ]; then
    curl -LO "https://github.com/babashka/babashka/releases/download/v${BABASHKA_VERSION}/babashka-${BABASHKA_VERSION}-${BABASHKA_ARCH}.tar.gz"
    tar -zxf "babashka-${BABASHKA_VERSION}-${BABASHKA_ARCH}.tar.gz" -C bin
fi
