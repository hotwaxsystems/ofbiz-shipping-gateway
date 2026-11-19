# Rules for agents and developers

This file tells a coding agent, or a person, how to work in this repo.
Read `docs/requirements.md`, `docs/design.md` and `docs/implementation-plan.md` first.
For FedEx, read `docs/fedex/fedex-api.md`.

## Where the code goes

| What | Where |
| --- | --- |
| The login entity | `entitydef/entitymodel.xml` |
| Seed data | `data/` |
| Service definitions | `servicedef/services.xml` |
| Shared services | `minilang/ShippingGatewayServices.xml` |
| One carrier | `minilang/<carrier>/<Carrier>Services.xml`, such as `minilang/fedex/FedexServices.xml` |
| The Java helpers | `src/main/java/co/hotwax/shippinggateway/` |

The plugin adds one entity, `ShipmentGatewayAuth`, for the login. Add no other entity without a design change first.

## How to write code

- Services are written in MiniLang. Only the request helper, the token helper and `ShipmentGatewayCredentials` are Java, and a carrier adds no Java.
- Services take a fully resolved packet and return a result packet, as the design defines.
- Packets are the same for every carrier, with unigate's field names. A carrier never adds its own packet fields; a new field goes in the design first.
- Never read the caller's business records, such as shipments, packages, addresses or orders. Read only setup records: `ShipmentGatewayConfig`, `ShipmentGatewayAuth`, `SystemProperty`, `CustomMethod`.
- Never write to the caller's records. Return the result, and the caller saves it.
- Find a carrier's service by its `CustomMethod` id: the carrier type, `_`, then the operation, such as `FEDEX_SHIP_LABEL`.
- Every HTTP call goes through `sendShipmentGatewayRequest`. It also writes the call log file.
- Never read a saved key or secret in MiniLang, except that `saveShipmentGatewayLogin` loads the row to update it. It never logs or returns the values.
- Never put `enable-audit-log` on an `encrypt="true"` field. The change log would store the plain value.
- No comments in code. Names and small steps say what the code does. A service keeps its short `<description>`.
- Write numbers into carrier requests as numbers in the request map, never as formatted text.
- Build reply readers from real or published carrier replies. Never guess a field name.
- Never fall back to a default carrier service. A missing service code is an error.

## JSON

- Only the request helper turns maps into JSON and JSON into maps. MiniLang builds and reads maps.
- The helper uses one shared Jackson `ObjectMapper`, held as a static final field. Never change its settings in place.
- Never build JSON by joining strings.
- Write dates as formatted text, never as numbers.
- Open every reader, writer, parser and generator in a try-with-resources block.
- Never store a carrier payload in the database. The call log file keeps it, without secrets, tokens or label images.

## Secrets and data

- Never write a key, secret or token to a log, a reply or a data file.
- Test data uses made-up businesses, people and addresses only.
- A real carrier reply quoted in a document has its account numbers and keys removed.

## Checks

- The repository holds no tests: no `src/test`, no `testdef`, no test suite in `ofbiz-component.xml`.
- Check each issue's "Done when" list by running it in a local OFBiz.
- Local checks call the carrier's test system, never the live one.
- Report what you ran and what came back in the pull request.

## Changes

- Every change goes through a pull request. Never commit to main.
- One issue per pull request. The pull request says which issue it closes.
- No AI attribution lines in commits or pull requests.
