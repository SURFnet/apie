#!/bin/sh

clojure -M \
        -m nl.jomco.eduhub-validator \
        -o ./ooapi.json \
        -r rules.yaml \
        -u "https://demo04.test.surfeduhub.nl/" \
        /courses
