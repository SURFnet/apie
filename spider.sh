#!/bin/sh

if [ "$#" = "1" ]; then
    :
else
    echo "Usage: $0 BASE_URL" >&2
    exit 1
fi

clojure -M \
        -m nl.jomco.eduhub-validator.spider \
        -o ooapi.json \
        -r rules.edn \
        -u "$1"
