# Eduhub API validator

A collection of command-line tools to spider and validate [Open
Education API](https://openonderwijsapi.nl/) endpoints to ensure
compatibility with services in
[SURFeduhub](https://www.surf.nl/surfeduhub-veilig-uitwisselen-van-onderwijsdata).

These are tools are intended for developers of OOAPI endpoints
at educational institutions or their software suppliers.

# Prerequisites

The tools in this repository require a Clojure runtime. You can
install either [Babashka](https://github.com/babashka/babashka#installation) for a standalone
environment with quick startup time and slightly slower runtime, or
the full [Clojure
installation](https://clojure.org/guides/install_clojure) which
requires Java and is slower to start.

The tools will use Babashka if `bb` is on the PATH, and will use
`clojure` otherwise.

# For endpoint developers

This repository contains the tools and configuration to validate a
"complete" OpenAPI endpoint with all of the paths available in the
specification.

Endpoints are not required to implement every path in the
specification. We are working on profiles (variant/subset OpenAPI
specifications) for different use-cases, like the RIO mapper
interface, which will be added to this repository.

Validating an endpoint works in two steps:

  - Spidering the endpoint and validating the responses. This will
    create a large file with "observations"; a sequence of
    request/response pairs and the associated validation issues.
    
  - Aggregating the observations into a readable HTML report.
  
## Spidering an endpoint

```sh
./validate --rules config/rules.edn \
  --spec config/rio-profile.json \
  --base-url https://your-endpoint/
```

This will exhaustively index your endpoint paths, validate against the
RIO profile and write the results to `observations.edn`. This file is
in [EDN format](https://github.com/edn-format/edn) which is similar to
JSON and can be read as text, but it will probably be very large.

## Spidering via gateway

To run the spider through the Eduhub gateway, you can use the
`--basic-auth` and `--headers` options:

```sh
./validate \
  --spec config/rio-profile.json \
  --rules config/rules.edn \
  --base-url https://gateway.test.surfeduhub.nl/ \
  --basic-auth USERNAME:PASS \
  --add-header 'x-route: endpoint=demo04.test.surfeduhub.nl' \
  --add-header 'accept: application/json; version=5' \
  --add-header 'x-envelope-response: false'
```

## Creating a human-readable report

After spidering is completed, a report is generated and written to
`report.html`. This report is readable in any web browser.

## On windows

Use `validate.bat` instead of the `validate` script.

## Available Eduhub profiles

A few Eduhub profiles are available in the [config](./config) directory:

  - `config/openapi.json` -- the full OOAPI v5 specification
  - `config/rio-profile.json` -- the RIO profile of OOAPI v5.
  
The RIO profile defines the subset of the OOAPI that RIO Mapper
service requires.

## Quick indexing

The `config/rules.edn` rules are exhaustive; using these will spider
all entities (courses, programs, offerings etc) available in the
endpoint.  This can take long time if there are many entities.  If you
want a quicker spidering phase, you can use `config/quick-rules.edn`
which will only index the first page of entities for each type (this
should be maximum of 20 entities per page):

```sh
./validate --rules config/quick-rules.edn \
  --spec config/rio-profile.json \
  --base-url https://your-endpoint/
```

# For specification authors

Information about writing specification profiles and spider rules can be
found in [docs/specification-authors.md](./docs/specification-authors.md).

# Component overview

![component diagram](./docs/components.png)

# Reporting vulnerabilities

If you have found a vulnerability in the code, we would like to hear
about it so that we can take appropriate measures as quickly as
possible. We are keen to cooperate with you to protect users and
systems better. See https://www.surf.nl/.well-known/security.txt for
information on how to report vulnerabilities responsibly.
