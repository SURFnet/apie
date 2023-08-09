#!/bin/sh

if bb -e '(System/exit 0)' 2>/dev/null; then
    RUNTIME="bb"
else
    RUNTIME="clojure -M"
fi

if [ "$#" = "1" ]; then
    :
else
    echo "Usage: $0 BASE_URL" >&2
    exit 1
fi

${RUNTIME} \
        -m nl.jomco.eduhub-validator.spider \
        -o ooapi.json \
        -r rules.edn \
        -u "$1"
