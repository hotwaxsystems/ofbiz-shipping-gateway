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

- Services are MiniLang. Only the three Java classes in task 2 are Java.
- The plugin adds one entity, `ShipmentGatewayAuth`, for the login. Every other record is a trunk record the design names.
- The plugin never reads or writes the caller's business records. It returns results, and the caller saves them.
- Write numbers plainly in carrier requests. A weight of 1234.5 must never become `1,234.5`.
- Build reply readers from real or published carrier replies. Never guess a field name.
- Never fall back to a default carrier service. A missing service code is an error.
- Keep the login token for each carrier account. Clear it when the login changes.
- Every carrier error has one shape: the carrier's status code and its reason.
- A carrier outage during an address check is `NOT_CHECKED`, never `INVALID`.
- A different city spelling alone never fails an address.
- Never write a secret or token to a log, a reply or a data file.
- The repository holds no tests. Check each task by running it in a local OFBiz.
- Local checks use FedEx's test system and made-up businesses and addresses.
- Every change goes through a pull request.

## Part 1. Shared frame

### 1. Login entity and seed data

Build:
- The `ShipmentGatewayAuth` entity, with its audited fields, as the design defines.
- Login types `SGA_OAUTH_CLIENT`, `SGA_BASIC` and `SGA_API_KEY_HEADER`, as `Enumeration` rows.
- Operation types `SHIP_LABEL`, `SHIP_VOID`, `SHIP_ACCOUNT_CHECK` and `SHIP_ADDRESS_CHECK`, as `CustomMethodType` rows.

Done when the plugin loads on OFBiz trunk, the table is created, and the seed data loads with no errors.

### 2. Java helpers

Build, in `src/main/java`, with no carrier name in the code:
- `getShipmentGatewayConnection` (MiniLang): returns the carrier type, the address (the login's `baseUrl`, else `{type}.url.test` or `{type}.url.live`), the mode and the account number, never a secret.
- `sendShipmentGatewayRequest`: sends GET, POST, PUT or DELETE for an account, with headers and a JSON or form body, and adds the login by its type. Turns the body map into JSON with its own Jackson mapper, set to write decimals as plain digits and dates as text. Returns the status code, the reply as a map, and `callOutcome`: `ANSWERED`, `TIMED_OUT` or `NOT_REACHED`.
- `getShipmentGatewayToken` and `clearShipmentGatewayToken`: read the key and secret from `ShipmentGatewayAuth`, get a token from the carrier's token address, keep it in an OFBiz cache per account until 5 minutes before it expires.
- `ShipmentGatewayCredentials`: static `encryptKey` and `decryptKey` for `publicKey`, with trunk's `delegator.encryptFieldValue` and `decryptFieldValue` on entity name `ShipmentGatewayAuth` and `EncryptMethod.TRUE`. `encryptKey` returns the saved text when the key is unchanged.

Done when a local run shows a GET, a POST and a PUT to a test address, a timeout reported as `TIMED_OUT`, and a second token request served from the cache.

### 3. Call log files

Build:
- In the request helper: set the Log4j2 thread context key `ofbiz.logFile` to `shippinggateway/{shipmentGatewayConfigId}/{yyyy-MM-dd}`, log the operation, reference ids, request and reply, then remove the key.
- A way to name fields to blank before logging, such as FedEx's `encodedLabel`, so label images never reach the log.
- The Routing block for `log4j2.xml`, and the `AppenderRef` line for trunk's `async` appender, from the design, in the repository's docs for deployments.
- The user's `userLoginId` on every logged call.

Done when a local call writes one file under `runtime/logs/process/shippinggateway/`, with no secret, token or label image in it, and the same lines are in `ofbiz.log`.

### 4. Shared services and carrier contracts

Build, in MiniLang:
- Interfaces `shipmentGatewayAccountCheckInterface`, `shipmentGatewayAddressCheckInterface`, `shipmentGatewayLabelInterface` and `shipmentGatewayVoidInterface`.
- Services `checkShipmentGatewayAccount`, `checkShipmentAddress`, `requestShipmentLabels` and `voidShipmentLabels`.
- Each one checks the packet, reads the account and its type, finds the `CustomMethod` named `{type}_{operation}`, and calls that service.
- `checkShipmentAddress` takes spaces out of the postal code, and returns `VALID`, `INVALID` or `NOT_CHECKED`.

Done when a local run calls each shared service with a made-up carrier type whose `CustomMethod` rows point at small stand-in services, and a missing row fails with a clear message.

## Part 2. Carrier template: FedEx

Tasks 5 to 10, with 7a, carry the label `carrier template`. The next carrier copies them.

### 5. Carrier seed data

Build:
- The `FEDEX` carrier party (`Party`, `PartyGroup`, `PartyRole` `CARRIER`) and the `ShipmentMethodType` rows the FedEx codes use. Trunk has them only in setup and demo data.
- The `FEDEX` gateway type and its four `CustomMethod` rows: `FEDEX_SHIP_LABEL`, `FEDEX_SHIP_VOID`, `FEDEX_SHIP_ACCOUNT_CHECK`, `FEDEX_SHIP_ADDRESS_CHECK`.
- FedEx's current service codes in `CarrierShipmentMethod`, such as `FEDEX_GROUND`.
- Test and live web addresses as `SystemProperty` rows.

Done when the seed loads on a database with only trunk seed data, and every FedEx method in the service list has a code.

### 6. Login and token

Build:
- `saveShipmentGatewayLogin`, gated by `SHIPPINGGATEWAY_ADMIN`. It creates or updates the `ShipmentGatewayAuth` row with its owner, type, mode, address, account number, user name and password, encrypts `publicKey` when given, and clears the token.
- The FedEx token call through `getShipmentGatewayToken`.

Done when two accounts get two tokens, `password` and `publicKey` are stored encrypted, a change of mode shows in `EntityAuditLog` with who and when, no password value is in `EntityAuditLog`, and a changed secret gets a new token.

### 7. Account check

Build `fedexCheckAccount`. It gets a token, then asks FedEx for one small rate quote between the two addresses in the packet. The quote needs the account number.

Done when local runs on FedEx's test system give: check passed, wrong secret, wrong account number.

### 7a. Address check

Build `fedexCheckAddress` with FedEx's address validation API:
- Send the address with spaces taken out of the postal code.
- Read the result from FedEx's resolved address and its attributes. The postal code comes from the postal code token.
- Return `VALID`, `INVALID` or `NOT_CHECKED`, the corrected form when it differs, and FedEx's reasons.
- Never fail an address on the city spelling alone.

Done when local runs give: good address, undeliverable address, city spelled differently, corrected form with ZIP+4, and FedEx not answering.

### 8. Label request

Build `fedexRequestShipmentLabels`:
- Build the FedEx request map from the packet only: the origin, company and destination addresses, each parcel's weight and size, and the service code.
- Read the reply: one tracking number and one label for each parcel, matched by `shipmentPackageSeqId`.
- Return the label result, with `testMode` set from the account. The caller saves it to its own route and package records.
- The label format comes from the packet. It can be PNG, PDF or ZPL.

Done when local runs give: one box, two boxes, FedEx refusing an address, and a timeout returned as `TIMED_OUT`.

### 9. Void

Build `fedexVoidShipmentLabels`. It cancels every box's label at FedEx by the master tracking number.

Done when local runs give: void, and void after shipping refused.

### 10. Every scenario on FedEx's test system

Needs a FedEx developer account and test keys.

Do:
- Set up a test account in a local OFBiz.
- Run each scenario in the requirements against FedEx's test system.
- Print one ZPL label and one PDF label, and check the barcode scans.
- Note in the issue what was run and what came back.

Done when each scenario passes against FedEx.

## Part 3. Coding agent skill

### 11. Write the "add a shipping carrier" skill

Write a skill in coding-agent-for-ofbiz. It walks a coding agent through tasks 5 to 10, with 7a, for a new carrier:
- the seed rows and MiniLang services to add, with the FedEx code as the example;
- how to use the three Java classes, and that a carrier adds no Java;
- the rules above;
- how to check each task in a local OFBiz.

Done when the skill is merged in coding-agent-for-ofbiz.

### 12. Check the skill with UPS

UPS is the second carrier. It is built only through the skill.

Do:
- Give a coding agent only the skill and UPS's API documents. It does tasks 5 to 10, with 7a, for UPS.
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
  - No UPS key is written in seed data.

Done when UPS passes every scenario on UPS's test system with no help beyond the skill.

## Order

Tasks 1 to 4 come first. Tasks 5 to 9 follow in order, with 7a after 7. Task 10 waits for the FedEx developer account. Task 11 starts after task 9. Task 12 comes last.
