# Eduhub API validator

A command-line tool to spider and validate [Open Education
API](https://openonderwijsapi.nl/) endpoints to ensure compatibility
with services in
[SURFeduhub](https://www.surf.nl/surfeduhub-veilig-uitwisselen-van-onderwijsdata).

This tool is intended for developers of OOAPI endpoints at educational
institutions or their software suppliers.

## Downloading a release

This repository contains the source code & configuration of the
validator. If you only need to run the builtin validations, download
the latest build for your platform from [the Releases
page](https://github.com/SURFnet/eduhub-validator/releases).

The released builds contain a standalone binary `eduhub-validator`
that has the configuration for multiple OOAPI profiles builtin.

## For OOAPI endpoint developers

The eduhub-validator binary contains a set of profiles which can be
used to validate an OpenAPI endpoint for specific use cases.

Endpoints are not required to implement every path in the
specification, 

Validating an endpoint works in two steps:

  - Spidering the endpoint and validating the responses. This will
    create a large file with "observations"; a sequence of
    request/response pairs and the associated validation issues.
    
  - Aggregating the observations into a readable HTML report.
  
## Spidering an endpoint directly

```sh
eduhub-validator --base-url https://your-endpoint/
```

This will exhaustively index your endpoint paths, validate against the
default `rio` profile and write a report to `report.html` which can be
opened using any web browser.

The intermediate validation results are written to
`observations.edn`. This file is in [EDN
format](https://github.com/edn-format/edn) which is similar to JSON
and can be read as text, but it will probably be very large.

## Spidering via gateway

To run the spider through the Eduhub gateway, you can use the
`--basic-auth` and `--headers` options:

```sh
eduhub-validator \
  --profile rio
  --base-url https://gateway.test.surfeduhub.nl/ \
  --basic-auth USERNAME:PASS \
  --add-header 'x-route: endpoint=demo04.test.surfeduhub.nl' \
  --add-header 'accept: application/json; version=5' \
  --add-header 'x-envelope-response: false'
```

## Available Eduhub profiles

A few Eduhub profiles are available in the [profiles](./profiles)
directory and are built into the binary releases:

  - `ooapi` -- the full OOAPI v5 specification
  - `rio` -- the RIO profile of OOAPI v5.
  
The RIO profile defines the subset of the OOAPI that RIO Mapper
service requires.

# Extending the validator

## Prerequisites for running/building from source

The source code in this repository requires a Clojure runtime. You can
install either
[Babashka](https://github.com/babashka/babashka#installation) for a
standalone environment with quick startup time and slightly slower
runtime, or the full [Clojure
installation](https://clojure.org/guides/install_clojure) which
requires Java and is slower to start.

The `validator` script in the root of the repository will use Babashka
if `bb` is on the PATH, and `clojure` otherwise.

## For specification authors

Information about writing specification profiles can be found in
[docs/specification-authors.md](./docs/specification-authors.md).

# Reporting vulnerabilities

If you have found a vulnerability in the code, we would like to hear
about it so that we can take appropriate measures as quickly as
possible. We are keen to cooperate with you to protect users and
systems better. See https://www.surf.nl/.well-known/security.txt for
information on how to report vulnerabilities responsibly.
