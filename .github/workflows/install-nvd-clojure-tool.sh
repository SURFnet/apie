#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2024, 2025 SURF B.V.
# SPDX-License-Identifier: EPL-2.0 WITH Classpath-exception-2.0
# SPDX-FileContributor: Remco van 't Veer

set -ex

# See also: https://github.com/rm-hull/nvd-clojure/pull/183
NVD_CLOJURE_REPO=https://github.com/jomco/nvd-clojure.git
NVD_CLOJURE_COMMIT=88b2150908fc42b5476ec5dddc7558457fa28d3e

if clojure -Ttools show '{:tool nvd}' | grep -q $NVD_CLOJURE_COMMIT; then
    :
else
    clojure -J-Dclojure.main.report=stderr \
            -Ttools install \
            nvd-clojure/nvd-clojure "{:git/url \"${NVD_CLOJURE_REPO}\" :git/sha \"${NVD_CLOJURE_COMMIT}\"}" :as nvd
fi
