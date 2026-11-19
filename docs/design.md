# Shipping gateway design

How the plugin meets the requirements with Apache OFBiz trunk.

## The main rule

The caller sends a fully resolved data packet. The plugin never reads the caller's business records.

- The caller, such as the WMS, reads its own shipments, addresses, packages and service codes. It builds the packet and sends it.
- The plugin turns the packet into the carrier's request, calls the carrier, and returns a result packet.
- The caller saves the result in its own records, such as the shipment route and its packages.
- The plugin reads only trunk records that hold its setup: the carrier account, its login settings and the service for each operation. It writes no business record.
- The plugin adds no entity. Every record it uses is an OFBiz trunk record.

This keeps the plugin usable by any OFBiz application, and easy to check with packets alone.

## How a label request flows

1. The caller builds a label packet: the carrier account, the origin, company and destination addresses, the carrier's service code, and the parcels.
2. The plugin reads the account's type, such as FedEx, and finds that carrier's label service from data.
3. The carrier service turns the packet into the carrier's request and calls the carrier.
4. The plugin writes the request and the reply to the account's call log file.
5. The plugin returns a result packet: the master tracking number, one tracking number and label per package, the charge, and any warnings.
6. The caller saves the result in its own records.

Example: the WMS builds a packet for shipment 10310, one box of 2.4 lb, FedEx Ground, account 10400.
The plugin calls `fedexRequestShipmentLabels`, and returns tracking number 794698745126 with the label.
The WMS writes the tracking number and label to its route records.

## Packets

The packets are uniform across all carriers. They follow the shared interfaces HotWax built in unigate, with the same field names, so a caller written for unigate fits here.
The carrier account is named by `shipmentGatewayConfigId`, OFBiz's own id, where unigate used `shippingGatewayAuthId`.

### Address map

Used for `originAddress`, `companyAddress`, `destAddress` and `payorAddress`.

| Field | Meaning |
| --- | --- |
| `toName`, `companyName` | Names on the label |
| `address1`, `address2` | Street lines |
| `city` | City |
| `stateOrProvinceCode` | Such as `UT` or `ON` |
| `postalCode` | Postal code |
| `countryCode` | Two letters, such as `US` or `CA` |
| `phoneCountryCode`, `phoneAreaCode`, `phoneNumber` | Phone, in parts |
| `emailAddress` | Email |
| `residential` | `true` or `false`, on `destAddress`, set by the caller |
| `facilityId`, `facilityName` | On `originAddress`, the warehouse |

### Label request: `shipmentGatewayLabelInterface`

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| `dateOfSale` | Ship date, `YYYY-MM-DD` |
| `primaryOrderId`, `orderName` | The caller's order id and the order's name, such as the sales channel's number |
| `originAddress` | Where the boxes leave from, the warehouse |
| `companyAddress` | The shipper of record, such as the merchant |
| `destAddress` | The recipient |
| `payorAddress`, `billingAccountNbr` | Only for third-party billing, later |
| `shipmentMethod` | The carrier's own service code, such as `FEDEX_GROUND`, resolved by the caller |
| `packagingType`, `pickupType` | The carrier's codes, such as `YOUR_PACKAGING` and `USE_SCHEDULED_PICKUP` |
| `labelImageType`, `labelSize` | `PDF`, `PNG` or `ZPLII`, and the stock, such as `STOCK_4X6` |
| `parcels` | List of boxes |

Each parcel:

| Field | Meaning |
| --- | --- |
| `shipmentPackageSeqId` | The caller's id for the box, returned with its tracking number |
| `weightValue`, `weightUnit` | Such as `2.4`, `LB` or `KG` |
| `length`, `width`, `height` | Optional, in the unit the caller states in `dimensionUnit`: `IN` or `CM` |
| `orderItems` | Optional, for customs, later |

### Label result

| Field | Meaning |
| --- | --- |
| `success` | `true` or `false` |
| `errorMessages` | The carrier's reason, when it refuses |
| `shippingLabelMap.referenceNumber` | The shipment's master tracking number |
| `shippingLabelMap.actualCarrierCode` | The carrier, such as `FEDEX` |
| `shippingLabelMap.trackingUrl` | The carrier's tracking page, when it gives one |
| `shippingLabelMap.packages` | List of `shipmentPackageSeqId`, `trackingIdNumber`, `imageBytes` (base64 label), `labelImageUrl` |

Added here, beyond unigate:
- `shipmentPackageSeqId` on each returned package, so the caller matches labels to boxes. Unigate matched by order only.
- `dimensionUnit` on each parcel. Unigate left the unit unstated.
- `shippingLabelMap.shippingCharge` and `currencyUomId`, the carrier's charge.
- `warningMessages`, carrier notes that come with a valid label, such as a postal code and city mismatch.
- `callOutcome`: `ANSWERED`, `TIMED_OUT` or `NOT_REACHED`. With `TIMED_OUT` the plugin does not know whether the carrier made the label.
- `testMode`: `true` when the label came from the carrier's test system. A test label is never used to ship.

### Void: `shipmentGatewayVoidInterface`

Unigate called this refund.

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| `trackingNumber` | The master tracking number |
| `trackingIds` | Optional, single boxes to void |
| Result: `success`, `errorMessages`, `callOutcome` | |

### Address check: `shipmentGatewayAddressCheckInterface`

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The address check account |
| `addresses` | List of `clientReferenceId` and `address` (address map) |
| Result: `success`, `errorMessages` | `success` false means the carrier was not reached: the addresses are not checked |
| Result: `results` | List of `clientReferenceId`, `isValid`, and `validationSummary` |

`validationSummary` holds `classification` (`RESIDENTIAL`, `BUSINESS`, `MIXED`, `UNKNOWN`), `standardizedAddress` (an address map, when the carrier's form differs) and `issue` (the carrier's reason).

The three results of the requirements come from this:
- `VALID`: `success` true and `isValid` true.
- `INVALID`: `success` true and `isValid` false.
- `NOT_CHECKED`: `success` false.

### Account check: `shipmentGatewayAccountCheckInterface`

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| `originAddress`, `destAddress` | Two addresses the caller owns, such as two of its warehouses. FedEx has no "check my account" call, so the check asks for a small rate quote between them |
| Result: `success`, `errorMessages`, `callOutcome` | |

### Rates, later: `shipmentGatewayRateInterface`

The same shape as unigate's rate interface: `originAddress`, `destAddress`, `parcels`, `shipmentMethod`, and a `shippingRates` list in the result.

## Data statements

1. A carrier account belongs to one carrier, such as FedEx.
2. A carrier account has one login. Its secret is kept encrypted.
3. A carrier account is in test mode or live mode.
4. A carrier account belongs to one merchant. The caller keeps that link, on its own records.
5. Each carrier names one service for each operation: label, void, address check, account check, and later rates.
6. Each shipping method may have the carrier's own code for it, such as `FEDEX_GROUND` for ground.
7. Each call to a carrier is written to a log file: the account, the operation, the user, the caller's reference ids, the request, the reply, whether it worked, and when.
8. A logged request or reply never holds a secret, a token or a label image.
9. A login token is kept in memory until it expires. It is never stored.
10. The plugin keeps no shipment, package, address or order data, and no check results. The caller keeps every result.

| Statement | Where it is kept |
| --- | --- |
| 1 | `ShipmentGatewayConfig.shipmentGatewayConfTypeId` |
| 2, 3 | `SystemProperty` rows of resource `shippinggateway`, one per setting, named after the account, written by `saveShipmentGatewayLogin` |
| 4 | The caller's records, such as `ProductStoreShipmentMeth.shipmentGatewayConfigId` |
| 5 | `CustomMethod`, found by its id |
| 6 | `CarrierShipmentMethod` |
| 7, 8 | Log files under `runtime/logs/process/shippinggateway/` |
| 9 | An OFBiz cache per account |

## Records

The plugin adds no entity (Expert User ruling, 2026-11-19).
Each record below says which trunk service writes it, so a developer can check the choice.

| Record | Use | Written by | Why this record |
| --- | --- | --- | --- |
| `ShipmentGatewayConfig` | One carrier account. Its type names the carrier. | trunk `createShipmentGatewayConfig` | Trunk's record for a carrier account, kept when OFBIZ-13447 removed the carrier tables |
| `ShipmentGatewayConfigType` | One row per carrier, such as `FEDEX`. Seeded. | trunk `createShipmentGatewayConfigType` | Trunk's carrier type for that account |
| `SystemProperty` | The account's login and mode, and each carrier's web addresses. | the plugin's `saveShipmentGatewayLogin`, which calls trunk `createEncryptedSecret` for the key and secret | Trunk keeps secrets here since its secret support of August 2026. Before OFBIZ-13447 the login sat in `ShipmentGatewayFedex`, which is gone |
| `CustomMethod`, `CustomMethodType` | Name the carrier's service for each operation. Seeded. | trunk `createCustomMethod` | Trunk's record for a service named in data, as in payment and shipping estimates |
| `CarrierShipmentMethod` | The carrier's own code for a shipping method, such as `FEDEX_GROUND`. Seeded for the caller to read. | trunk `createCarrierShipmentMethod` | Trunk's record for a carrier's service code. The old Java carrier code read it the same way |
| `ShipmentMethodType` | The method types the seeded codes use. | trunk `createShipmentMethodType` | Trunk has them only in demo and setup data, so the plugin seeds the ones it uses |
| `Party`, `PartyGroup`, `PartyRole` for `FEDEX` | The carrier party that `CarrierShipmentMethod` points to. | seed data, as trunk's own setup data does | Trunk has FedEx only in setup and demo data |

The plugin never reads `Shipment`, `ShipmentPackage`, `ShipmentRouteSegment`, `PostalAddress`, `ProductStoreShipmentMeth` or any order record.

### The login in `SystemProperty`

Trunk dropped its carrier login tables in June 2026 (OFBIZ-13447), and kept only `ShipmentGatewayConfig`.
Trunk's secret support (August 2026) keeps a secret in `SystemProperty`:
- `createEncryptedSecret` stores it as `ENC(...)`, encrypted with the `OFBIZ_MASTER_KEY` environment value. It needs the `SECRET_MAINT` permission.
- A row can also point to an outside secret store with `systemPropertyLookup`.
- `EntityUtilProperties.getPropertyValue` returns the plain value in both cases.

Rows for account 10400, resource `shippinggateway`:

| `systemPropertyId` | Value |
| --- | --- |
| `10400.accountNumber` | `740561073` |
| `10400.apiKey` | the API key, encrypted |
| `10400.apiSecret` | the secret, encrypted |
| `10400.testMode` | `Y` or `N` |

Only the token helper reads the key and the secret. They never enter a log line or a reply.

The plugin's service `saveShipmentGatewayLogin` records a login. It needs `SHIPPINGGATEWAY_ADMIN`.
1. It writes `accountNumber` and `testMode` as plain `SystemProperty` rows.
2. It calls trunk `createEncryptedSecret` for `apiKey` and `apiSecret`, with `secretTarget` `SYSTEM_PROPERTY`, as the system user. Trunk also writes a `SecretAuditLog` row.
3. It calls `clearShipmentGatewayToken`, so the next call gets a new token.

This keeps trunk's `SECRET_MAINT` permission away from a Shipping Admin. That permission covers every secret, including database passwords.

### Which service does each operation

Each operation is a `CustomMethodType`: `SHIP_LABEL`, `SHIP_VOID`, `SHIP_ACCOUNT_CHECK`, `SHIP_ADDRESS_CHECK`.
The carrier's service for an operation is the `CustomMethod` whose id is the carrier type and the operation joined by `_`.
For example, `FEDEX_SHIP_LABEL` names `fedexRequestShipmentLabels`.
A missing row fails the call with "no SHIP_LABEL service for carrier FEDEX".

Why a built id: trunk points to a `CustomMethod` from a field on a setup record, such as `ProductStorePaymentSetting.paymentCustomMethodId` for each payment operation. No trunk record holds a method per carrier type and operation, and the plugin adds no entity. The id rule is the smallest honest replacement, and it is written here so nobody has to guess it.
A later rate interface uses trunk's own `SHIP_EST` type.

### Seed data

```xml
<entity-engine-xml>
    <CustomMethodType customMethodTypeId="SHIP_LABEL" hasTable="N" description="Shipment Gateway label methods"/>
    <CustomMethodType customMethodTypeId="SHIP_VOID" hasTable="N" description="Shipment Gateway void label methods"/>
    <CustomMethodType customMethodTypeId="SHIP_ACCOUNT_CHECK" hasTable="N" description="Shipment Gateway account check methods"/>
    <CustomMethodType customMethodTypeId="SHIP_ADDRESS_CHECK" hasTable="N" description="Shipment Gateway address check methods"/>

    <Party partyId="FEDEX" partyTypeId="PARTY_GROUP" statusId="PARTY_ENABLED"/>
    <PartyGroup partyId="FEDEX" groupName="Federal Express"/>
    <PartyRole partyId="FEDEX" roleTypeId="CARRIER"/>
    <ShipmentMethodType shipmentMethodTypeId="GROUND" description="Ground"/>
    <ShipmentMethodType shipmentMethodTypeId="SECOND_DAY" description="Second Day"/>

    <ShipmentGatewayConfigType shipmentGatewayConfTypeId="FEDEX" hasTable="N" description="FedEx REST API"/>
    <CustomMethod customMethodId="FEDEX_SHIP_LABEL" customMethodTypeId="SHIP_LABEL" customMethodName="fedexRequestShipmentLabels"/>
    <CustomMethod customMethodId="FEDEX_SHIP_VOID" customMethodTypeId="SHIP_VOID" customMethodName="fedexVoidShipmentLabels"/>
    <CustomMethod customMethodId="FEDEX_SHIP_ACCOUNT_CHECK" customMethodTypeId="SHIP_ACCOUNT_CHECK" customMethodName="fedexCheckAccount"/>
    <CustomMethod customMethodId="FEDEX_SHIP_ADDRESS_CHECK" customMethodTypeId="SHIP_ADDRESS_CHECK" customMethodName="fedexCheckAddress"/>

    <CarrierShipmentMethod shipmentMethodTypeId="GROUND" partyId="FEDEX" roleTypeId="CARRIER" carrierServiceCode="FEDEX_GROUND"/>
    <CarrierShipmentMethod shipmentMethodTypeId="SECOND_DAY" partyId="FEDEX" roleTypeId="CARRIER" carrierServiceCode="FEDEX_2_DAY"/>

    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="fedex.url.test" systemPropertyValue="https://apis-sandbox.fedex.com"/>
    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="fedex.url.live" systemPropertyValue="https://apis.fedex.com"/>
</entity-engine-xml>
```

The block shows two methods. The seed file has every method in the FedEx service list in `docs/fedex/fedex-api.md`, with its `ShipmentMethodType` row.
Rows that trunk's setup or demo data already loads, such as `FEDEX`, are the same values, so loading both is safe.
Old FedEx codes from trunk's setup data, such as `FIRSTOVERNIGHT`, stay for methods the plugin does not seed. A caller uses only the methods in the FedEx service list.

### Sample: one carrier account

```xml
<entity-engine-xml>
    <ShipmentGatewayConfig shipmentGatewayConfigId="10400" shipmentGatewayConfTypeId="FEDEX" description="Acme FedEx"/>
    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="10400.accountNumber" systemPropertyValue="740561073"/>
    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="10400.testMode" systemPropertyValue="Y"/>
</entity-engine-xml>
```

The key and the secret are never in a data file. A person records them with `createEncryptedSecret`.
A caller links the account to its own records, for example a store shipping method's `shipmentGatewayConfigId`.

## Services

### Shared services, called by any application

| Service | In | Out |
| --- | --- | --- |
| `checkShipmentGatewayAccount` | Account check packet | Account check result |
| `checkShipmentAddress` | Address check packet | Address check result |
| `requestShipmentLabels` | Label request packet | Label result |
| `voidShipmentLabels` | Void request packet | Void result |

Each shared service:
1. Checks the packet has every required field. A missing field fails before any carrier call.
2. Reads the account and its type.
3. Finds the carrier service by its `CustomMethod` id, and calls it.
4. Returns the result packet. A carrier refusal is a result with `success` false, not an exception.

### Contracts every carrier follows

| Interface | Implemented by FedEx as |
| --- | --- |
| `shipmentGatewayAccountCheckInterface` | `fedexCheckAccount` |
| `shipmentGatewayLabelInterface` | `fedexRequestShipmentLabels` |
| `shipmentGatewayVoidInterface` | `fedexVoidShipmentLabels` |
| `shipmentGatewayAddressCheckInterface` | `fedexCheckAddress` |
| later, for rates | a rate packet, in the same style |

The interfaces take and return the packets above, so every carrier gets the same input.

### Address check rules

- Spaces are taken out of the postal code before the call.
- A different city spelling alone never makes the result `INVALID`.
- A carrier error or timeout returns `NOT_CHECKED`, never `INVALID`.
- The plugin returns the result. The caller decides what to do, such as holding an order.

## How the code is built

Services are MiniLang (Expert User ruling, 2026-11-19). Two small Java helpers do what MiniLang cannot. Neither names a carrier.

| Part | Written in | Job |
| --- | --- | --- |
| Shared services | MiniLang | Check the packet, find the account and the carrier service, call it |
| Carrier services, such as `fedexRequestShipmentLabels` | MiniLang | Turn the packet into the carrier's request map, call the request helper, read the reply map into the result packet |
| `sendShipmentGatewayRequest` | Java | Send one HTTP call: GET, POST, PUT or DELETE, with headers and a JSON or form body. Return the status code, the reply as a map, and the call outcome |
| `getShipmentGatewayToken` | Java | Return a login token for an account. Read the key and secret from `SystemProperty`, call the carrier's token address, keep the token in an OFBiz cache until 5 minutes before it expires |
| `clearShipmentGatewayToken` | Java | Drop an account's cached token, when its login changes |
| `saveShipmentGatewayLogin` | MiniLang | Record an account's login and mode, then clear its token |

Retries:
- On a 401 reply, the request helper gets a new token once and repeats the call once.
- On a 429, 500 or 503 reply, or a timeout, the plugin does not repeat the call. It returns the result, and the caller decides when to try again.

Why Java for these two:
- Trunk's `HttpClient` sends only GET and POST. FedEx voids a label with PUT.
- A request body is turned into JSON by the helper's own Jackson mapper, set to write decimals as plain digits and dates as text. Trunk's `JSON` class uses default settings, which can write `1E+2` and dates as numbers.
- The call outcome needs to tell a timeout from a refusal.
- The secret stays inside Java. MiniLang's `property-to-field` expands the value it reads, so it is not used for secrets.

All FedEx services sit in one MiniLang file, `minilang/fedex/FedexServices.xml`. The next carrier gets its own file.

## Call log files

Each call to a carrier is written to a log file, as Maarg's Data Manager does (`hotwax-maarg-util`, `log4j2.xml`).

1. The request helper puts a key in the Log4j2 thread context before the call: `shippinggateway/{shipmentGatewayConfigId}/{yyyy-MM-dd}`.
2. It logs the operation, the user's `userLoginId`, the caller's reference ids, the request and the reply. Secrets, tokens and label images are taken out first.
3. A Routing appender in `log4j2.xml` sends every line with that key to `runtime/logs/process/{key}.log`. The lines also stay in `ofbiz.log`.
4. The helper removes the key after the call.

Example: account 10400 asks for a label at 10:02 on 19 November for order 1051, and FedEx refuses it.
The request and FedEx's reply are in `runtime/logs/process/shippinggateway/10400/2026-11-19.log`, next to the other calls of that day.

Rotation, as Maarg's (Expert User ruling, 2026-11-19): a new file at 50 MB, five old copies kept, nothing deleted by age. Each account gets a new file each day, and daily files are never deleted, so the folder grows until someone removes old files.
An idle file is closed after 5 minutes.

The Routing block goes in the deployment's `log4j2.xml`, because trunk ships none. It needs two changes, the block and one line in trunk's `async` appender:

```xml
<Routing name="ProcessLogRouter">
    <Routes pattern="$${ctx:ofbiz.logFile}">
        <Route key="${ctx:ofbiz.logFile}"><Null name="process-log-unset"/></Route>
        <Route key=""><Null name="process-log-empty"/></Route>
        <Route>
            <RollingFile name="process-${ctx:ofbiz.logFile}"
                    fileName="runtime/logs/process/${ctx:ofbiz.logFile}.log"
                    filePattern="runtime/logs/process/${ctx:ofbiz.logFile}-%i.log">
                <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p %m%n"/>
                <Policies><SizeBasedTriggeringPolicy size="50 MB"/></Policies>
                <DefaultRolloverStrategy max="5"/>
            </RollingFile>
        </Route>
    </Routes>
    <IdlePurgePolicy timeToLive="5" timeUnit="minutes"/>
</Routing>
```

```xml
<Async name="async" includeLocation="${includeLocation}">
    <AppenderRef ref="ofbiz"/>
    <AppenderRef ref="stdout"/>
    <AppenderRef ref="error"/>
    <AppenderRef ref="ProcessLogRouter"/>
</Async>
```

Without the block, the lines still reach `ofbiz.log`. Nothing breaks.

## What the caller does

For a warehouse application, such as the WMS:

1. Picks the carrier account, for example from the store shipping method.
2. Builds the packet from its records: the facility address as `originAddress`, the merchant as `companyAddress`, the ship-to address as `destAddress`, each shipment package as a parcel, and `shipmentMethod` from `CarrierShipmentMethod`.
3. Sets `residential` from its last address check of the ship-to address.
4. Calls the shared service.
5. Saves the result: the route's tracking number, cost and carrier status, and each package's tracking number and label. It also keeps the account check and address check results it needs.
6. Voids the labels when a shipment with labels is cancelled.
7. On `callOutcome` `TIMED_OUT`, checks with the carrier before it asks for the label again.

## Security

- Services need the plugin's `SHIPPINGGATEWAY` permission.
- The secret is encrypted and never returned, logged or searched by.
- Recording a login needs `SHIPPINGGATEWAY_ADMIN`. The plugin calls trunk's secret service for the person.
- Nothing in the plugin knows about the owner of an account. An application that needs owners, such as a warehouse serving many merchants, adds that itself.

## Checks

- The repository holds no automatic tests.
- A developer checks each change by sending packets to the services in a local OFBiz. No shipment or order records are needed.
- Local checks call FedEx's test system, never the live one.
- Checks use made-up accounts and addresses.
- A person runs every scenario against FedEx's test system before each release.

## Decisions

These settle the points that were open for the design review. Each one can change in review, and the change goes in this section.

1. Resolved packets: the caller sends every value the carrier needs. The plugin never reads the caller's business records (Expert User ruling, 2026-11-12).
2. Language: services are MiniLang. Two small Java helpers send the HTTP call and keep the token (Expert User ruling, 2026-11-19: "Our Preference is Minilang").
3. Records: no new entity. The login is in `SystemProperty`, the service for each operation is a `CustomMethod` found by its id (Expert User ruling, 2026-11-19: "we don't want to add new entities").
4. Call log: log files through a Log4j2 Routing appender, one file per account per day, with Maarg's rotation. Not `CommunicationEvent`, which is for messages between parties (Expert User rulings, 2026-11-19).
5. Route set-up and route status: the caller's job, since the route is the caller's record.
6. Residential flag: set by the caller. The address check returns `classification` to help it.
7. Uniform packets: the packets follow unigate's shared interfaces and field names, for every carrier (Expert User ruling, 2026-11-12). `pickupType`, `packagingType`, `labelImageType` and `labelSize` come in the packet, as in unigate.
8. Label stock: the caller sends `labelSize`. Earlier FedEx work found `STOCK_4X6` right for ZPL, and `PAPER_4X6` for PDF and PNG.
9. Unknown outcome: on a timeout the result says `TIMED_OUT`, so the caller does not buy a second label by mistake.
