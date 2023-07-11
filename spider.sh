#!/bin/sh

clojure -M \
        -m nl.jomco.eduhub-validator.spider \
        -o ./ooapi.json \
        -r rules.edn \
        -u "https://demo04.test.surfeduhub.nl/" \
        "$@"
