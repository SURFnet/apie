# Eduhub validator

A collection of tools to create API profiles, spider and validate
[OOAPI](https://openonderwijsapi.nl/) endpoints to ensure compatibility with services in [SURFeduhub](https://www.surf.nl/surfeduhub-veilig-uitwisselen-van-onderwijsdata).

## Profiles

Create profiles by making parts of the OOAPI stricter using [merge-yaml-tree](https://git.sr.ht/~jomco/merge-yaml-tree).

## Spider

Configure and run a rules based HTTP
[spider](https://git.sr.ht/~jomco/spider) to probe API endpoints.

## Validator

Pull the recorded interactions of the spider and the profiles through
[openapi-v3-validator](https://git.sr.ht/~jomco/openapi-v3-validator)
to evaluate the correctness of the API requests and response.
