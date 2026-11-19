# Shipping gateway design

How the plugin meets the requirements with Apache OFBiz trunk.

## The main rule

The caller sends a fully resolved data packet. The plugin never reads the caller's business records.

- The caller, such as the WMS, reads its own shipments, addresses, packages and service codes. It builds the packet and sends it.
- The plugin turns the packet into the carrier's request, calls the carrier, and returns a result packet.
- The caller saves the result in its own records, such as the shipment route and its packages.
- The plugin reads only setup records: the carrier account, its login and the service for each operation. It writes no business record.
- The plugin adds one entity, `ShipmentGatewayAuth`, for the login. Every other record it uses is an OFBiz trunk record.

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
2. A carrier account has one login. Its secret and key are kept encrypted. Nobody can read them back.
3. A login says how the carrier checks it: a key and secret for a token, a user name and password, or a key sent in a header.
4. A login is for the carrier's test system or its live system, and has the web address it calls.
5. A login has the carrier's account number.
6. A login has an owner, the merchant party. One merchant's account can serve all of its stores.
7. Each change to a login is recorded: what changed, who changed it, and when. A change of the password is not recorded.
8. Each carrier names one service for each operation: label, void, address check, account check, and later rates.
9. Each shipping method may have the carrier's own code for it, such as `FEDEX_GROUND` for ground.
10. Each call to a carrier is written to a log file: the account, the operation, the user, the caller's reference ids, the request, the reply, whether it worked, and when.
11. A logged request or reply never holds a secret, a token or a label image.
12. A login token is kept in memory until it expires. It is never stored.
13. The plugin keeps no shipment, package, address or order data, and no check results. The caller keeps every result.

| Statement | Where it is kept |
| --- | --- |
| 1 | `ShipmentGatewayConfig.shipmentGatewayConfTypeId` |
| 2 to 6 | `ShipmentGatewayAuth`, one row per carrier account, written by `saveShipmentGatewayLogin` |
| 7 | `EntityAuditLog`, from the audited fields of `ShipmentGatewayAuth` |
| Which store uses which account | The caller's records, such as `ProductStoreShipmentMeth.shipmentGatewayConfigId` |
| 8 | `CustomMethod`, found by its id |
| 9 | `CarrierShipmentMethod` |
| 10, 11 | Log files under `runtime/logs/process/shippinggateway/` |
| 12 | An OFBiz cache per account |

## Records

The plugin adds one entity, `ShipmentGatewayAuth`, for the login (Expert User ruling, 2026-11-19, after reading unigate's `ShippingGatewayAuth`). Every other record is trunk's.
Each record below says which service writes it, so a developer can check the choice.

| Record | Use | Written by | Why this record |
| --- | --- | --- | --- |
| `ShipmentGatewayConfig` | One carrier account. Its type names the carrier. | trunk `createShipmentGatewayConfig` | Trunk's record for a carrier account, kept when OFBIZ-13447 removed the carrier tables |
| `ShipmentGatewayConfigType` | One row per carrier, such as `FEDEX`. Seeded. | trunk `createShipmentGatewayConfigType` | Trunk's carrier type for that account |
| `ShipmentGatewayAuth` (plugin) | The account's login, owner, mode and address. | the plugin's `saveShipmentGatewayLogin` | Trunk kept a login table per carrier, keyed by the account id, until OFBIZ-13447 removed them. Unigate's `ShippingGatewayAuth` showed that one shape serves every carrier. Unlike unigate, a login has no id of its own: one row per account, because the account row already belongs to one merchant. And the secret and the key are both encrypted |
| `SystemProperty` | Each carrier's default test and live web addresses. Seeded. | seed data | Trunk's record for settings; used when a login has no `baseUrl` |
| `Enumeration`, `EnumerationType` | The login types. Seeded. | seed data | Trunk's record for a fixed list of codes, as unigate's `authTypeEnumId` |
| `EntityAuditLog` | Each change to an audited login field. | the entity engine, for fields marked `enable-audit-log` | Trunk's change log |
| `CustomMethod`, `CustomMethodType` | Name the carrier's service for each operation. Seeded. | trunk `createCustomMethod` | Trunk's record for a service named in data, as in payment and shipping estimates |
| `CarrierShipmentMethod` | The carrier's own code for a shipping method, such as `FEDEX_GROUND`. Seeded for the caller to read. | trunk `createCarrierShipmentMethod` | Trunk's record for a carrier's service code. The old Java carrier code read it the same way |
| `ShipmentMethodType` | The method types the seeded codes use. | trunk `createShipmentMethodType` | Trunk has them only in demo and setup data, so the plugin seeds the ones it uses |
| `Party`, `PartyGroup`, `PartyRole` for `FEDEX` | The carrier party that `CarrierShipmentMethod` points to. | seed data, as trunk's own setup data does | Trunk has FedEx only in setup and demo data |

The plugin never reads `Shipment`, `ShipmentPackage`, `ShipmentRouteSegment`, `PostalAddress`, `ProductStoreShipmentMeth` or any order record.

### The login: `ShipmentGatewayAuth`

One row per carrier account. It learns from unigate's `ShippingGatewayAuth`: one shape for every carrier, an address per login, and changes recorded.

```xml
<entity entity-name="ShipmentGatewayAuth" package-name="co.hotwax.shippinggateway"
        title="The login for one carrier account">
    <field name="shipmentGatewayConfigId" type="id"/>
    <field name="ownerPartyId" type="id" enable-audit-log="true"/>
    <field name="authTypeEnumId" type="id" enable-audit-log="true"/>
    <field name="testMode" type="indicator" enable-audit-log="true"/>
    <field name="baseUrl" type="url" enable-audit-log="true"/>
    <field name="accountNumber" type="short-varchar" enable-audit-log="true"/>
    <field name="username" type="value" enable-audit-log="true"/>
    <field name="password" type="value" encrypt="true"/>
    <field name="publicKey" type="value" enable-audit-log="true"/>
    <field name="authHeaderName" type="short-varchar" enable-audit-log="true"/>
    <field name="description" type="description"/>
    <prim-key field="shipmentGatewayConfigId"/>
    <relation type="one" fk-name="SG_AUTH_CONFIG" rel-entity-name="ShipmentGatewayConfig">
        <key-map field-name="shipmentGatewayConfigId"/>
    </relation>
    <relation type="one" fk-name="SG_AUTH_OWNER" title="Owner" rel-entity-name="Party">
        <key-map field-name="ownerPartyId" rel-field-name="partyId"/>
    </relation>
    <relation type="one" fk-name="SG_AUTH_TYPE" title="AuthType" rel-entity-name="Enumeration">
        <key-map field-name="authTypeEnumId" rel-field-name="enumId"/>
    </relation>
</entity>
```

| Field | Meaning | Example |
| --- | --- | --- |
| `shipmentGatewayConfigId` | The carrier account. One login each. | `10400` |
| `ownerPartyId` | The merchant party that owns the account (Expert User ruling, 2026-11-19). | `ACME` |
| `authTypeEnumId` | How the carrier checks the login. | `SGA_OAUTH_CLIENT` |
| `testMode` | `Y` for the carrier's test system, `N` for live. | `Y` |
| `baseUrl` | The address this login calls. Empty means the carrier's default for the mode. | `https://apis-sandbox.fedex.com` |
| `accountNumber` | The carrier's account number. | `740561073` |
| `username` | The user name, for a user name and password login. Plain. | `acme-shipper` |
| `password` | The password, or the secret for a token login. Encrypted by the entity engine. | the FedEx secret |
| `publicKey` | The API key: the client id for a token login, or the key sent in a header. Encrypted by `saveShipmentGatewayLogin`. | the FedEx API key |
| `authHeaderName` | The header that carries the key, for a key sent in a header. | `X-Api-Key` |

Why each field is stored the way it is:
- `password` is marked `encrypt="true"`. The entity engine encrypts it, and it is never in the change log. The change log stores values as it reads them, and it reads encrypted fields decrypted (`GenericDelegator.createEntityAuditLogSingle`). Its changes are not recorded (Expert User ruling, 2026-11-19).
- `publicKey` is a plain field. `saveShipmentGatewayLogin` encrypts it through `ShipmentGatewayCredentials`, and the helpers decrypt it the same way. The change log then stores the encrypted text, so a change is recorded without showing the key (Expert User ruling, 2026-11-19). The idea comes from Maarg, which encrypts some values in code; the call here is trunk's own.
- `ShipmentGatewayCredentials.encryptKey(Delegator, String)` calls `delegator.encryptFieldValue("ShipmentGatewayAuth", EncryptMethod.TRUE, value)`. `decryptKey(Delegator, String)` calls `decryptFieldValue` with the same entity name and method. MiniLang calls them with `call-class-method`, passing `delegator` with `type="org.apache.ofbiz.entity.Delegator"`.
- Trunk encrypts with a new random value each time, so the same key gives new text on each save. `encryptKey` takes the saved text too, and returns it unchanged when the new key is the same. So an unchanged key never adds a false "changed" row.
- `password` and `publicKey` hold at most 150 characters before encryption. The encrypted text must fit the 255-character field. The save service refuses a longer value.
- Why the secret is in `password` and the key in `publicKey`: unigate kept the FedEx secret in `publicKey` and the key in `username`, both plain. Here the secret is in the entity-encrypted field, and the key in the service-encrypted field, so neither is ever stored or logged as plain text.
- No field is searched by its value. An encrypted field cannot be searched.

Login types, seeded:

| `enumId` | Meaning | Fields used |
| --- | --- | --- |
| `SGA_OAUTH_CLIENT` | Key and secret buy a token, as FedEx and UPS do | `publicKey` (the key), `password` (the secret) |
| `SGA_BASIC` | User name and password on each call | `username`, `password` |
| `SGA_API_KEY_HEADER` | A key sent in a header on each call | `publicKey`, `authHeaderName` |

Only the Java helpers read `password` and `publicKey`. `saveShipmentGatewayLogin` loads the row to update it, and never logs or returns it. Neither value enters a log line or a reply.

The plugin's service `saveShipmentGatewayLogin` records a login. It needs `SHIPPINGGATEWAY_ADMIN`.
1. It checks the account exists and the login type is one of the seeded types.
2. It encrypts `publicKey`, when given, with `delegator.encryptFieldValue`.
3. It creates or updates the `ShipmentGatewayAuth` row. An empty `password` or `publicKey` on an update keeps the saved one.
4. It calls `clearShipmentGatewayToken`, so the next call gets a new token.

The plugin stores the owner. The caller decides who may use an account. For example, the WMS refuses when one merchant links another merchant's account to its store.

### Which service does each operation

Each operation is a `CustomMethodType`: `SHIP_LABEL`, `SHIP_VOID`, `SHIP_ACCOUNT_CHECK`, `SHIP_ADDRESS_CHECK`.
The carrier's service for an operation is the `CustomMethod` whose id is the carrier type and the operation joined by `_`.
For example, `FEDEX_SHIP_LABEL` names `fedexRequestShipmentLabels`.
A missing row fails the call with "no SHIP_LABEL service for carrier FEDEX".

Why a built id: trunk points to a `CustomMethod` from a field on a setup record, such as `ProductStorePaymentSetting.paymentCustomMethodId` for each payment operation. No trunk record holds a method per carrier type and operation, and the plugin adds only the login entity. The id rule is the smallest honest replacement, and it is written here so nobody has to guess it.
A later rate interface uses trunk's own `SHIP_EST` type.

### Seed data

```xml
<entity-engine-xml>
    <EnumerationType enumTypeId="SHIP_GW_AUTH_TYPE" hasTable="N" description="Shipment gateway login type"/>
    <Enumeration enumId="SGA_OAUTH_CLIENT" enumTypeId="SHIP_GW_AUTH_TYPE" sequenceId="01" description="Key and secret for a token"/>
    <Enumeration enumId="SGA_BASIC" enumTypeId="SHIP_GW_AUTH_TYPE" sequenceId="02" description="User name and password"/>
    <Enumeration enumId="SGA_API_KEY_HEADER" enumTypeId="SHIP_GW_AUTH_TYPE" sequenceId="03" description="Key in a header"/>

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
    <ShipmentGatewayAuth shipmentGatewayConfigId="10400" ownerPartyId="ACME" authTypeEnumId="SGA_OAUTH_CLIENT"
            testMode="Y" accountNumber="740561073" description="Acme FedEx test login"/>
</entity-engine-xml>
```

The key and the secret are never in a data file. A person records them with `saveShipmentGatewayLogin`.
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

Services are MiniLang (Expert User ruling, 2026-11-19). Three small Java classes do what MiniLang cannot. None names a carrier.

| Part | Written in | Job |
| --- | --- | --- |
| Shared services | MiniLang | Check the packet, find the account and the carrier service, call it |
| Carrier services, such as `fedexRequestShipmentLabels` | MiniLang | Turn the packet into the carrier's request map, call the request helper, read the reply map into the result packet |
| `sendShipmentGatewayRequest` | Java | Send one HTTP call: GET, POST, PUT or DELETE, with headers and a JSON or form body. Return the status code, the reply as a map, and the call outcome |
| `getShipmentGatewayToken` | Java | Return a login token for an account. Read the key and secret from `ShipmentGatewayAuth`, call the carrier's token address, keep the token in an OFBiz cache until 5 minutes before it expires |
| `clearShipmentGatewayToken` | Java | Drop an account's cached token, when its login changes |
| `ShipmentGatewayCredentials` | Java | Static methods that encrypt and decrypt `publicKey` with trunk's `delegator.encryptFieldValue` and `decryptFieldValue`. The save service calls the encrypt method with `call-class-method` |
| `saveShipmentGatewayLogin` | MiniLang | Record an account's login, encrypt `publicKey`, then clear its token |

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
- Recording a login needs `SHIPPINGGATEWAY_ADMIN`.
- Each change to a login, except the password, is in `EntityAuditLog`.
- The plugin stores each account's owner. The caller decides who may use an account.

## Checks

- The repository holds no automatic tests.
- A developer checks each change by sending packets to the services in a local OFBiz. No shipment or order records are needed.
- Local checks call FedEx's test system, never the live one.
- Checks use made-up accounts and addresses.
- A person runs every scenario against FedEx's test system before each release.

## Decisions

These settle the points that were open for the design review. Each one can change in review, and the change goes in this section.

1. Resolved packets: the caller sends every value the carrier needs. The plugin never reads the caller's business records (Expert User ruling, 2026-11-12).
2. Language: services are MiniLang. Three small Java classes send the HTTP call, keep the token and encrypt the key (Expert User ruling, 2026-11-19: "Our Preference is Minilang").
3. Records: one new entity, `ShipmentGatewayAuth`, for the login, owned by the merchant party, with changes recorded (Expert User rulings, 2026-11-19). Every other record is trunk's. The service for each operation is a `CustomMethod` found by its id.
4. Call log: log files through a Log4j2 Routing appender, one file per account per day, with Maarg's rotation. Not `CommunicationEvent`, which is for messages between parties (Expert User rulings, 2026-11-19).
5. Route set-up and route status: the caller's job, since the route is the caller's record.
6. Residential flag: set by the caller. The address check returns `classification` to help it.
7. Uniform packets: the packets follow unigate's shared interfaces and field names, for every carrier (Expert User ruling, 2026-11-12). `pickupType`, `packagingType`, `labelImageType` and `labelSize` come in the packet, as in unigate.
8. Label stock: the caller sends `labelSize`. Earlier FedEx work found `STOCK_4X6` right for ZPL, and `PAPER_4X6` for PDF and PNG.
9. Unknown outcome: on a timeout the result says `TIMED_OUT`, so the caller does not buy a second label by mistake.
