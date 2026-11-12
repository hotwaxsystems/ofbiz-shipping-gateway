# Shipping gateway design

How the plugin meets the requirements with Apache OFBiz trunk.

## The main rule

The caller sends a fully resolved data packet. The plugin never reads the caller's business records.

- The caller, such as the WMS, reads its own shipments, addresses, packages and service codes. It builds the packet and sends it.
- The plugin turns the packet into the carrier's request, calls the carrier, and returns a result packet.
- The caller saves the result in its own records, such as the shipment route and its packages.
- The plugin reads and writes only its own records: carrier accounts, logins, which service does each operation, tokens, and the call log.

This keeps the plugin usable by any OFBiz application, and easy to test with packets alone.

## How a label request flows

1. The caller builds a label packet: the carrier account, shipper, recipient, service code, packages and references.
2. The plugin reads the account's type, such as FedEx, and finds that carrier's label service from data.
3. The carrier service turns the packet into the carrier's request and calls the carrier.
4. The plugin records the call in its call log.
5. The plugin returns a result packet: the master tracking number, one tracking number and label per package, the charge, and any warnings.
6. The caller saves the result in its own records.

Example: the WMS builds a packet for shipment 10310, one box of 2.4 lb, FedEx Ground, account 10400.
The plugin calls `fedexRequestShipmentLabels`, and returns tracking number 794698745126 with the label.
The WMS writes the tracking number and label to its route records.

## Packets

Packets are maps. Field names are the same for every carrier.

### Address

| Field | Meaning |
| --- | --- |
| `streetLines` | List of street lines |
| `city` | City |
| `stateOrProvinceCode` | Such as `UT` or `ON` |
| `postalCode` | Postal code |
| `countryCode` | Two letters, such as `US` or `CA` |
| `residential` | `true` or `false`, set by the caller |

### Party

| Field | Meaning |
| --- | --- |
| `companyName`, `personName` | Names on the label |
| `phoneNumber`, `emailAddress` | Contact |
| `address` | An address packet |

### Label request

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| `labelFormat` | `PDF`, `PNG` or `ZPL` |
| `shipDate` | `YYYY-MM-DD` |
| `serviceCode` | The carrier's own code, such as `FEDEX_GROUND`, resolved by the caller |
| `shipper`, `recipient` | Party packets |
| `packages` | List of packages |
| `references` | Map of the caller's ids, such as `orderId`, `externalOrderId`, `shipmentId`. Kept in the call log and sent to the carrier as references |

Each package:

| Field | Meaning |
| --- | --- |
| `packageId` | The caller's id for the box, returned with its tracking number |
| `weight`, `weightUnit` | Such as `2.4`, `LB` or `KG` |
| `length`, `width`, `height`, `dimensionUnit` | Optional, `IN` or `CM` |

### Label result

| Field | Meaning |
| --- | --- |
| `success` | `true` or `false` |
| `masterTrackingNumber` | The shipment's tracking number |
| `packages` | List of `packageId`, `trackingNumber`, `labelFormat`, `labelContent` (base64) |
| `totalCharge`, `currencyCode` | The carrier's charge |
| `warnings` | List of carrier code and message, such as a postal code and city mismatch |
| `errorCode`, `errorMessage` | The carrier's reason, when it refuses |
| `shipmentGatewayResponseId` | The call log row |

### Void request and result

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| `masterTrackingNumber` | The shipment's tracking number |
| `references` | The caller's ids |
| Result: `success`, `errorCode`, `errorMessage`, `shipmentGatewayResponseId` | |

### Address check request and result

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The address check account |
| `address` | An address packet |
| `references` | The caller's ids, such as `contactMechId` |
| Result: `addressCheckResult` | `VALID`, `INVALID` or `NOT_CHECKED` |
| Result: `classification` | `RESIDENTIAL`, `BUSINESS`, `MIXED` or `UNKNOWN` |
| Result: `correctedAddress` | An address packet, when the carrier's form differs |
| Result: `carrierMessages` | The carrier's reasons |

### Account check

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account |
| Result: `passed`, `errorCode`, `errorMessage` | |

## Records

### From trunk, used by the plugin

| Record | Use |
| --- | --- |
| `ShipmentGatewayConfig` | One carrier account. Its type names the carrier. |
| `ShipmentGatewayConfigType` | One row per carrier, seeded by the plugin, such as `FEDEX`. |
| `CustomMethod`, `CustomMethodType` | Name a service in data. |
| `SystemProperty` | Each carrier's test and live web addresses. |

### From trunk, used by the caller, seeded by the plugin

| Record | Use |
| --- | --- |
| `CarrierShipmentMethod` | The carrier's own code for a shipping method, such as `FEDEX_GROUND` for `GROUND`. The plugin seeds current codes. The caller reads them to fill `serviceCode`. |
| `ShipmentMethodType` | The method types the seeded codes use. |

The plugin never reads `Shipment`, `ShipmentPackage`, `ShipmentRouteSegment`, `PostalAddress`, `ProductStoreShipmentMeth` or any order record.

### Added by the plugin

`ShipmentGatewayConfTypeMethod` names the service for one carrier and one operation:

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfTypeId` | The carrier, such as `FEDEX` (key) |
| `customMethodTypeId` | The operation, such as `SHIP_LABEL` (key) |
| `customMethodId` | The `CustomMethod` that names the carrier's service |

This follows trunk's payment setup, where `ProductStorePaymentSetting` names a service for each payment operation.

`ShipmentGatewayResponse` records each call to a carrier. It follows trunk's `PaymentGatewayResponse`:

| Field | Meaning |
| --- | --- |
| `shipmentGatewayResponseId` | Id (key) |
| `shipmentGatewayConfigId` | The carrier account |
| `customMethodTypeId` | The operation |
| `referenceIds` | The caller's `references`, as given, as JSON. No link to the caller's records |
| `resultSuccess` | `Y` or `N` |
| `gatewayCode`, `gatewayMessage` | The carrier's code and reason |
| `requestMessage`, `responseMessage` | The request and reply, without secrets, tokens or label images |
| `transactionDate`, `createdByUserLogin` | When, and who |

The last account check of an account is its newest `SHIP_ACCOUNT_CHECK` row here.

`ShipmentGatewayFedex` holds a FedEx account's login, one row per carrier account:

| Field | Meaning |
| --- | --- |
| `shipmentGatewayConfigId` | The carrier account (key) |
| `accountNumber` | FedEx account number |
| `apiKey` | FedEx API key |
| `apiSecret` | FedEx secret, `encrypt="true"` |
| `testMode` | `Y` uses FedEx's test address, `N` the live one |
| `pickupType` | How FedEx collects the boxes: `USE_SCHEDULED_PICKUP` or `DROPOFF_AT_FEDEX_LOCATION` |

Each later carrier adds its own login record in the same shape, such as `ShipmentGatewayUps`.

### Seed data

```xml
<entity-engine-xml>
    <CustomMethodType customMethodTypeId="SHIP_LABEL" hasTable="N" description="Shipment Gateway label methods"/>
    <CustomMethodType customMethodTypeId="SHIP_VOID" hasTable="N" description="Shipment Gateway void label methods"/>
    <CustomMethodType customMethodTypeId="SHIP_ACCOUNT_CHECK" hasTable="N" description="Shipment Gateway account check methods"/>
    <CustomMethodType customMethodTypeId="SHIP_ADDRESS_CHECK" hasTable="N" description="Shipment Gateway address check methods"/>

    <ShipmentGatewayConfigType shipmentGatewayConfTypeId="FEDEX" hasTable="Y" description="FedEx REST API"/>
    <CustomMethod customMethodId="FEDEX_LABEL" customMethodTypeId="SHIP_LABEL" customMethodName="fedexRequestShipmentLabels"/>
    <CustomMethod customMethodId="FEDEX_VOID" customMethodTypeId="SHIP_VOID" customMethodName="fedexVoidShipmentLabels"/>
    <CustomMethod customMethodId="FEDEX_CHECK" customMethodTypeId="SHIP_ACCOUNT_CHECK" customMethodName="fedexCheckAccount"/>
    <CustomMethod customMethodId="FEDEX_ADDRESS" customMethodTypeId="SHIP_ADDRESS_CHECK" customMethodName="fedexCheckAddress"/>
    <ShipmentGatewayConfTypeMethod shipmentGatewayConfTypeId="FEDEX" customMethodTypeId="SHIP_LABEL" customMethodId="FEDEX_LABEL"/>
    <ShipmentGatewayConfTypeMethod shipmentGatewayConfTypeId="FEDEX" customMethodTypeId="SHIP_VOID" customMethodId="FEDEX_VOID"/>
    <ShipmentGatewayConfTypeMethod shipmentGatewayConfTypeId="FEDEX" customMethodTypeId="SHIP_ACCOUNT_CHECK" customMethodId="FEDEX_CHECK"/>
    <ShipmentGatewayConfTypeMethod shipmentGatewayConfTypeId="FEDEX" customMethodTypeId="SHIP_ADDRESS_CHECK" customMethodId="FEDEX_ADDRESS"/>

    <CarrierShipmentMethod shipmentMethodTypeId="GROUND" partyId="FEDEX" roleTypeId="CARRIER" carrierServiceCode="FEDEX_GROUND"/>
    <CarrierShipmentMethod shipmentMethodTypeId="SECOND_DAY" partyId="FEDEX" roleTypeId="CARRIER" carrierServiceCode="FEDEX_2_DAY"/>

    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="fedex.url.test" systemPropertyValue="https://apis-sandbox.fedex.com"/>
    <SystemProperty systemResourceId="shippinggateway" systemPropertyId="fedex.url.live" systemPropertyValue="https://apis.fedex.com"/>
</entity-engine-xml>
```

The full FedEx service list is in `docs/fedex/fedex-api.md`.

### Sample: one carrier account

```xml
<entity-engine-xml>
    <ShipmentGatewayConfig shipmentGatewayConfigId="10400" shipmentGatewayConfTypeId="FEDEX" description="Acme FedEx"/>
    <ShipmentGatewayFedex shipmentGatewayConfigId="10400" accountNumber="740561073" apiKey="l7a1b2c3d4e5f6"
            apiSecret="(encrypted)" testMode="Y" pickupType="USE_SCHEDULED_PICKUP"/>
</entity-engine-xml>
```

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
3. Finds the carrier service in `ShipmentGatewayConfTypeMethod`, and calls it.
4. Writes a `ShipmentGatewayResponse` row in its own transaction, so a failed call is still recorded.
5. Returns the result packet. A carrier refusal is a result with `success` false, not an exception.

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

## What the caller does

For a warehouse application, such as the WMS:

1. Picks the carrier account, for example from the store shipping method.
2. Builds the packet from its records: the facility address as the shipper, the ship-to address as the recipient, each shipment package, and the service code from `CarrierShipmentMethod`.
3. Sets `residential` from its last address check of the ship-to address.
4. Calls the shared service.
5. Saves the result: the route's tracking number, cost and carrier status, and each package's tracking number and label.
6. Voids the labels when a shipment with labels is cancelled.

## Inside the FedEx package

- All FedEx code sits in one package, `co.hotwax.shippinggateway.fedex`. The next carrier gets its own package.
- FedEx uses an OAuth login: the API key and secret buy a token that lasts about an hour. The token is kept in an OFBiz cache per account until it expires.
- Every HTTP call goes through one small FedEx client class. It uses the HTTP client built into Java, so no extra library is needed.
- The FedEx request is built from the packet only. `docs/fedex/fedex-api.md` maps every packet field to a FedEx field.
- Labels come back as base64 text, in PNG, PDF or ZPL format.

## Security

- Services need the plugin's `SHIPPINGGATEWAY` permission.
- The secret is encrypted and never returned, logged or searched by.
- Nothing in the plugin knows about the owner of an account. An application that needs owners, such as a warehouse serving many merchants, adds that itself.

## Tests

- Tests send packets and check result packets. They need no shipment or order records.
- The FedEx client is replaced with one that returns saved FedEx replies. No test calls FedEx.
- Saved replies use made-up accounts and addresses.
- A person tests against FedEx's test system by hand before each release.

## Decisions

These settle the points that were open for the design review. Each one can change in review, and the change goes in this section.

1. Resolved packets: the caller sends every value the carrier needs. The plugin never reads the caller's business records (Expert User ruling, 2026-11-12).
2. Language: all services are written in Groovy. Carrier calls need HTTP and JSON, and one language keeps the plugin simple.
3. Route set-up and route status: the caller's job, since the route is the caller's record.
4. Call log size: request and reply text is kept up to 64 KB each. Longer text is cut, and the row says it was cut. Label images are never kept in the log.
5. Residential flag: set by the caller. The address check returns `classification` to help it.
6. Pickup type: kept on the FedEx account record, so each account says how its boxes leave the warehouse.
7. Label stock: `STOCK_4X6` for ZPL, `PAPER_4X6` for PDF and PNG, as earlier FedEx work found for thermal printers.
