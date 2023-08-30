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
./spider.sh -r rules.edn -o openapi.json \
  -u https://your-endpoint/ \
  -p observations.edn
```

This will exhaustively index your endpoint paths and print the
resulting observations to `observations.edn`. This file is in [EDN
format](https://github.com/edn-format/edn) which is similar to JSON
and can be read as text, but it will probably be very large.

## Spidering via gateway

_Work in progress_

To run the spider through the Eduhub gateway, you can use the
`--basic-auth` and `--headers` options:

```sh
./spider.sh \
  -o openapi.json \
  -r rules.edn \
  -u https://gateway.test.surfeduhub.nl/ \
  --basic-auth USERNAME:PASS \
  -h 'x-route: endpoint=demo04.test.surfeduhub.nl' \
  -h 'accept: application/json; version=5' \
  -h 'x-envelope-response: false' \
  -p observations.edn
```

Currently the test gateway does not correctly process the
`x-envelope-response` header so the above will result in a lot of
validation issues related to the response envelope.

## Creating a report

After spidering is completed, you can create a readable report using

```sh
./report.sh -o openapi.json -p report.html observations.edn
```

This report is readable in any web browser.

## On windows

Use `spider.bat` and `report.bat` instead of the `.sh` scripts.

# For specification editors

## Profiles

Profiles are variants of an OpenAPI definition. We create profiles of
the OpenOnderwijsAPI spec for different use cases.

Profiles are created using
[merge-yaml-tree](https://git.sr.ht/~jomco/merge-yaml-tree). We will
include a collection of pre-made profiles in this repository in the
near future.

## Rules format

Rules files are `edn` maps and have two keys:

- `:seeds` - a list of request maps. Request maps need at least a
  `:method` and a `:path` key. Seeds represent the initial requests
  for spidering. There must be at least one seed in order to spider an
  endpoint.

- `:rules` - a list of rule maps. A rule will `:match` an interaction
  map and `:generates` one or more request maps.
  

A rule will match a particular interaction if every clause in the
`match` list matches. A clause represents a path of literal values and
placeholders. This `generates` a list of requests from a template that
can use the placeholders:

```clojure
  {:match     [[:request, :method, "get"]
               [:request, :path, ?path]
               [:response :status 200]
               [:response, :body, "pageNumber", ?pageNumber]
               [:response, :body, "hasNextPage", true]]
   :generates [{:method "get"
                :path   "{?path}?pageNumber={(inc ?pageNumber)}"}]}
```

Literal entries are integers, keywords (starting with `:`) and quoted
strings. Placeholders are symbols (identifiers) starting with a `?`.

In the above example we have a match if all of the following hold:

- The request is a GET request, with the path matching placeholder
  `?path`
- The response has 200 OK status
- The response body has a field `"pageNumber"` stored in placeholder
  `?pageNumber`
- Response body has a field `"hasNextPage"` with value `true`.

This will generate one GET request on the same `?path` with a
`pageNumber` parameter that is one more than the `?pageNumber` in the
interaction's response.

The reponse body is automatically parsed as json if it has the correct
content type.

## Template format

You can insert expressions in the `:generates` template by using
`{...}` brackets. Placeholders are available in expressions.
S-expressions can be used for function calls.
  
The following functions are available in expressions:

- `(inc EXPR)` increases EXPR by one
- `(dec EXPR)` decreases EXPR by one
- `(+ A B)` add A to B
- `(- A B)` subtract B from A
- `(not EXPR)` boolean not
- `(= A B)` true if A equals B

# Component overview

![component diagram](./components.png)

