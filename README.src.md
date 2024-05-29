# Apie 🙈 OpenAPI Service Validator

A command-line tool to spider and validate API endpoints to ensure
compatibility with OpenAPI v3 specs.

Apie takes an OpenAPI description of your service and crawls the
endpoints, evaluating the interactions as it goes. It then generates a
compact report containing all the validation issues found.

# SYNOPSIS

<!-- INCLUDE USAGE HERE -->

# For service developers

Use Apie to get quick and readable feedback during development.

Run Apie from your automated tests to prevent regressions.

Apie helps you to validate that your service is behaving according to
spec. Apie is a free, open source, standalone tool that talks to your
service like any other HTTP client. If you have an OpenAPI v3
specification for your API, you need minimal configuration to specify
seed points and rules.

# For standards bodies

Use Apie to ensure adherence to your standards.

When you're publishing specifications for others to implement, you
want to provide all the automated support you can. With Apie, you can
take your service description as a standard OpenAPI v3 document, add a
simple rule set and quickly evaluate any implementation to ensure
adherence!

# Example

```sh
apie --profile example-profiles/petstore.edn \
     --base-url https://petstore3.swagger.io/api/v3
```

This will spider the paths in the profile, validate against the
included openapi specification and write a report to `report.html`
which can be opened using any web browser. See the [petstore example
report](https://surfnet.github.io/apie/example-report.html).

The intermediate validation results are written to
`observations.edn`. This file is in [EDN
format](https://github.com/edn-format/edn) which is similar to JSON
and can be read as text, but it will probably be very large.

See our [configuration documentation](./docs/specification-authors.md)
for more details.

## Downloading a release

This repository contains the source code & example configuration of
Apie. If you only need to run the your own validations, download the
latest build for your platform from [the Releases
page](https://github.com/SURFnet/apie/releases).

The released builds contain a standalone binary `apie`.

# Developing Apie

## Prerequisites for running/building from source

The source code in this repository requires a Clojure runtime. You can
install either
[Babashka](https://github.com/babashka/babashka#installation) for a
standalone environment with quick startup time and slightly slower
runtime, or the full [Clojure
installation](https://clojure.org/guides/install_clojure) which
requires Java and is slower to start.

For running the validator without building, you can use the
`dev/validate` or `dev/validate.bat` script.

The `dev/validate` script will use Babashka if `bb` is on the PATH,
and `clojure` otherwise.

Alternatively, start a Clojure REPL and go from there.

## Building Apie

The Makefile can build a release for your current operating system:

```
make apie  # on linux / macos
```

or

```
make apie.exe  # for windows (untested)
```

We build releases for all supported platforms on Github; see
`.github/workflows/build.yaml` for details.

# Reporting vulnerabilities

If you have found a vulnerability in the code, we would like to hear
about it so that we can take appropriate measures as quickly as
possible. We are keen to cooperate with you to protect users and
systems better. See https://www.surf.nl/.well-known/security.txt for
information on how to report vulnerabilities responsibly.
