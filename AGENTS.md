# Rules for agents and developers

This file tells a coding agent, or a person, how to work in this repo.
Read `docs/requirements.md`, `docs/design.md` and `docs/implementation-plan.md` first.
For FedEx, read `docs/fedex/fedex-api.md`.

## Where the code goes

| What | Where |
| --- | --- |
| Records | `entitydef/entitymodel.xml` |
| Seed data | `data/` |
| Service definitions | `servicedef/services.xml` |
| Shared services | `src/main/groovy/co/hotwax/shippinggateway/` |
| One carrier | `src/main/groovy/co/hotwax/shippinggateway/<carrier>/`, such as `fedex/` |
| Tests | `testdef/` and `src/test/groovy/` |
| Saved carrier replies | `src/test/resources/<carrier>/`, one file per scenario |

## How to write code

- Services are written in Groovy, using OFBiz's Groovy service style.
- No comments in code. Names and small steps say what the code does. A service keeps its short `<description>`.
- Use OFBiz trunk records first. Add a record only when trunk has none, as the design says.
- One shared contract. Code outside a carrier's package never names a carrier.
- HTTP calls go through the plugin's one HTTP client, so tests can replace it with saved replies.
- Write numbers into carrier requests as plain digits, never with a thousands comma.
- Build reply readers from real or published carrier replies. Never guess a field name.
- Never fall back to a default carrier service. A missing service code is an error.

## Secrets and data

- Never write a key, secret or token to a log, the call log, a reply or a test file.
- Test data uses made-up businesses, people and addresses only.
- Real carrier replies are saved only after account numbers and keys are removed.

## Tests

- Every issue's "Done when" list becomes tests.
- Tests use the saved replies. No automatic test calls a real carrier.
- Run the plugin's tests with `./gradlew "ofbiz --test component=shippinggateway"` from the OFBiz checkout.

## Changes

- Every change goes through a pull request. Never commit to main.
- One issue per pull request. The pull request says which issue it closes.
- No AI attribution lines in commits or pull requests.
