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
./spider.sh https://your-endpoint/ >observations.edn
```

This will exhaustively index your endpoint paths and print the
resulting observations to `observations.edn`. This file is in [EDN
format](https://github.com/edn-format/edn) which is similar to JSON
and can be read as text, but it will probably be very large.

## Creating a report

After spidering is completed, you can create a readable report using

```sh
./report.sh observations.edn >report.html
```

This report is readable in any web browser.

# For specification editors

## Profiles

Profiles are variants of an OpenAPI definition. We create profiles of
the OpenOnderwijsAPI spec for different use cases.

Profiles are created using
[merge-yaml-tree](https://git.sr.ht/~jomco/merge-yaml-tree). We will
include a collection of pre-made profiles in this repository in the
near future.

# Component overview

![component diagram](./components.png)

