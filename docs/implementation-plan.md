# Implementation plan

How we build the shipping gateway plugin, FedEx first.
Each numbered task is one GitHub issue.

The plan has three parts:

1. **Shared frame.** Built once, used by every carrier.
2. **Carrier template.** Repeated for each carrier. FedEx is the first.
3. **Coding agent skill.** Turns what we learned into a skill, so the next carrier is easy.

It builds on the [requirements](requirements.md) and the [design](design.md).

## Rules for every task

These come from earlier FedEx integrations at HotWax, and from the fixes they needed.

- Write numbers plainly in carrier requests. A weight of 1234.5 must never become `1,234.5`.
- Build reply readers from real carrier replies, saved as test files. Never guess a field name.
- Never fall back to a default carrier service. A missing service code is an error.
- Keep the login token for each carrier account. Clear it when the account changes.
- Every carrier error has one shape: the carrier's status code and its reason.
- Never write a secret or token to a log, the call log or a reply.
- Test data uses made-up businesses and addresses only.
- Every change goes through a pull request.

## Part 1. Shared frame

### 1. Records and seed data

Build:
- `ShipmentGatewayConfTypeMethod`: the service for each carrier and operation.
- `ShipmentGatewayResponse`: the log of carrier calls.
- Operation types `SHIP_LABEL`, `SHIP_VOID` and `SHIP_ACCOUNT_CHECK`, as `CustomMethodType` rows.

Done when the plugin loads on OFBiz trunk, and the records and seed data load with no errors.

### 2. Shared services and carrier contracts

Build:
- Interfaces `shipmentGatewayAccountCheckInterface`, `shipmentGatewayLabelInterface` and `shipmentGatewayVoidInterface`.
- Services `checkShipmentGatewayAccount`, `requestShipmentLabels` and `voidShipmentLabels`.
- Each shared service finds the account, finds the carrier's service, and calls it in its own transaction.
- `voidShipmentLabels` refuses a shipped shipment.

Done when a test carrier, made only for tests, is called through each shared service.

### 3. HTTP client, call log and error shape

Build:
- One small HTTP client with the HTTP client built into Java. It sends JSON and form requests and returns status and body.
- The call log writer. It writes one `ShipmentGatewayResponse` row per call, in its own transaction, without secrets, tokens or label images.
- The error shape: the carrier's status code and reason, returned as a service error.

Done when a failed call is in the call log and the caller gets the carrier's reason.

### 4. Test kit with saved replies

Build:
- A way for tests to replace the HTTP client with one that returns saved replies from files.
- A folder layout for saved replies: one folder per carrier, one file per scenario.
- Shared test data: a made-up business, store, warehouse, customer address and shipment with boxes.

Done when a test runs a shared service end to end, with no network.

## Part 2. Carrier template: FedEx

Tasks 5 to 11 carry the label `carrier template`. The next carrier copies them.

### 5. Carrier seed data

Build:
- The `FEDEX` gateway type and its `ShipmentGatewayConfTypeMethod` rows.
- FedEx's current service codes in `CarrierShipmentMethod`, such as `FEDEX_GROUND`.
- Test and live web addresses as `SystemProperty` rows.

Done when the seed loads, and every store shipping method for FedEx has a service code.

### 6. Login record and token

Build:
- `ShipmentGatewayFedex`, with the secret encrypted.
- The FedEx login call: the API key and secret buy a token.
- A token cache for each account. It expires before FedEx's token does. It is cleared when the account changes.

Done when two accounts get two tokens, and a changed secret gets a new token.

### 7. Account check

Build `fedexCheckAccount`. It gets a token, then makes one small FedEx call that needs the account number.

Done when the saved-reply tests pass for: check passed, wrong secret, wrong account number.

### 8. Label request

Build `fedexRequestShipmentLabels`:
- Build the FedEx request from OFBiz records: the warehouse address, the customer address, each box's weight and size, and the service code.
- Read the reply: one tracking number and one label for each box.
- Save to `ShipmentRouteSegment` and `ShipmentPackageRouteSeg`, and move the route to accepted.
- The label format comes from the request. It can be PNG, PDF or ZPL.

Done when the saved-reply tests pass for: one box, two boxes, and FedEx refusing an address.

### 9. Void

Build:
- `fedexVoidShipmentLabels`. It cancels every box's label at FedEx and moves the route to voided.
- An automatic void when a shipment with labels is cancelled.

Done when the saved-reply tests pass for: void, void after shipping refused, and void on cancel.

### 10. Saved replies and scenario tests

Build one test for each scenario in the requirements, using saved FedEx replies.

Done when every scenario in the requirements has a passing test.

### 11. Manual test on FedEx's test system

Needs a FedEx developer account and test keys.

Do:
- Set up a test account in a local OFBiz.
- Run each scenario against FedEx's test system.
- Save any new real replies as test files, without account numbers or keys.
- Print one ZPL label and one PDF label, and check the barcode scans.

Done when each scenario passes against FedEx, and the results are noted in the issue.

## Part 3. Coding agent skill

### 12. Write the "add a shipping carrier" skill

Write a skill in coding-agent-for-ofbiz. It walks a coding agent through tasks 5 to 11 for a new carrier:
- the records and services to add, with the FedEx code as the example;
- the rules above;
- how to save carrier replies and write the tests;
- the checks before a pull request.

Done when the skill is merged in coding-agent-for-ofbiz.

### 13. Test the skill with a second carrier

Do:
- Pick a second carrier.
- Give a coding agent only the skill and the carrier's API documents.
- Note every place the agent needed help, then fix the skill.

Done when the second carrier passes its tests with no help beyond the skill.

## Order

Tasks 1 to 4 come first. Tasks 5 to 10 follow in order. Task 11 waits for the FedEx developer account. Task 12 starts after task 10. Task 13 comes last.
