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
- Build reply readers from real or published carrier replies. Never guess a field name.
- Never fall back to a default carrier service. A missing service code is an error.
- Keep the login token for each carrier account. Clear it when the account changes.
- Every carrier error has one shape: the carrier's status code and its reason.
- A carrier outage during an address check is `NOT_CHECKED`, never `INVALID`.
- A different city spelling alone never fails an address.
- Never write a secret or token to a log, the call log or a reply.
- Test data uses made-up businesses and addresses only.
- Every change goes through a pull request.

## Part 1. Shared frame

### 1. Records and seed data

Build:
- `ShipmentGatewayConfTypeMethod`: the service for each carrier and operation.
- `ShipmentGatewayResponse`: the log of carrier calls.
- Operation types `SHIP_LABEL`, `SHIP_VOID`, `SHIP_ACCOUNT_CHECK` and `SHIP_ADDRESS_CHECK`, as `CustomMethodType` rows.

Done when the plugin loads on OFBiz trunk, and the records and seed data load with no errors.

### 2. Shared services and carrier contracts

Build:
- Interfaces `shipmentGatewayAccountCheckInterface`, `shipmentGatewayAddressCheckInterface`, `shipmentGatewayLabelInterface` and `shipmentGatewayVoidInterface`.
- Services `checkShipmentGatewayAccount`, `checkShipmentAddress`, `requestShipmentLabels` and `voidShipmentLabels`.
- `checkShipmentAddress` takes spaces out of the postal code, and returns `VALID`, `INVALID` or `NOT_CHECKED`.
- Each shared service finds the account, finds the carrier's service, and calls it in its own transaction.
- `voidShipmentLabels` refuses a shipped shipment.

Done when each shared service, run in a local OFBiz, finds the account and calls the carrier service set for it.

### 3. HTTP client, call log and error shape

Build:
- One small HTTP client with the HTTP client built into Java. It sends JSON and form requests and returns status and body.
- The call log writer. It writes one `ShipmentGatewayResponse` row per call, in its own transaction, without secrets, tokens or label images.
- The error shape: the carrier's status code and reason, returned as a service error.

Done when a failed call is in the call log and the caller gets the carrier's reason.

### 4. Dropped

The repository holds no tests, so no test kit is built.

## Part 2. Carrier template: FedEx

Tasks 5 to 11, with 7a and without 10, carry the label `carrier template`. The next carrier copies them.

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

Done when a local check gives the right result for: check passed, wrong secret, wrong account number.

### 7a. Address check

Build `fedexCheckAddress` with FedEx's address validation API:
- Send the address with spaces taken out of the postal code.
- Read the result from FedEx's resolved address and its attributes. The postal code comes from the postal code token.
- Return `VALID`, `INVALID` or `NOT_CHECKED`, the corrected form when it differs, and FedEx's reasons.
- Never fail an address on the city spelling alone.

Done when a local check gives the right result for: good address, undeliverable address, city spelled differently, corrected form with ZIP+4, and FedEx not answering.

### 8. Label request

Build `fedexRequestShipmentLabels`:
- Build the FedEx request from OFBiz records: the warehouse address, the customer address, each box's weight and size, and the service code.
- Read the reply: one tracking number and one label for each box.
- Save to `ShipmentRouteSegment` and `ShipmentPackageRouteSeg`, and move the route to accepted.
- The label format comes from the request. It can be PNG, PDF or ZPL.

Done when a local check gives the right result for: one box, two boxes, and FedEx refusing an address.

### 9. Void

Build:
- `fedexVoidShipmentLabels`. It cancels every box's label at FedEx and moves the route to voided.
- An automatic void when a shipment with labels is cancelled.

Done when a local check gives the right result for: void, void after shipping refused, and void on cancel.

### 10. Dropped

The repository holds no tests. Task 11 runs every scenario against FedEx's test system.

### 11. Manual test on FedEx's test system

Needs a FedEx developer account and test keys.

Do:
- Set up a test account in a local OFBiz.
- Run each scenario against FedEx's test system.
- Print one ZPL label and one PDF label, and check the barcode scans.

Done when each scenario passes against FedEx, and the results are noted in the issue.

## Part 3. Coding agent skill

### 12. Write the "add a shipping carrier" skill

Write a skill in coding-agent-for-ofbiz. It walks a coding agent through tasks 5 to 11, with 7a, for a new carrier:
- the records and services to add, with the FedEx code as the example;
- the rules above;
- how to check each operation in a local OFBiz against the carrier's test system;
- the checks before a pull request.

Done when the skill is merged in coding-agent-for-ofbiz.

### 13. Test the skill with UPS

UPS is the second carrier. It is built only through the skill.

Do:
- Give a coding agent only the skill and UPS's API documents. It does tasks 5 to 11, with 7a, for UPS.
- Note every place the agent needed help, then fix the skill.
- Check the result against what earlier HotWax UPS work learned. The skill passes only if the agent handled each point without being told:
  - The address check uses UPS's city, state and ZIP check, without the street.
  - An address passes when UPS keeps the same state and 5-digit ZIP. A ZIP+4 extension is ignored.
  - No suggested address from UPS means the address fails.
  - Names and addresses are escaped in the JSON, such as "O'Fallon".
  - An empty optional field is left out of the request.
  - UPS errors are read from its list of codes and messages.
  - The login token is kept for its full life, about 4 hours.
  - UPS web addresses carry an API version and are kept in settings.
  - Saturday delivery is set when the order's estimated delivery date is a Saturday.
  - No UPS key is written in seed data.

Done when every UPS scenario passes a local check with no help beyond the skill, and the skill covers every point above.

## Order

Tasks 1 to 3 come first. Tasks 5 to 9 follow in order, with 7a after 7. Tasks 6 to 11 need the FedEx developer account and test keys. Task 12 starts after task 11. Task 13 comes last.
