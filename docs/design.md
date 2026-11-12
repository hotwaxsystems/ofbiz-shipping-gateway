# Shipping gateway design

How the plugin meets the requirements with Apache OFBiz trunk records and services.

## How a label request flows

1. The caller asks for labels for one shipment route.
2. The plugin finds the order's store shipping method, then its carrier account. Trunk's `getShipmentGatewayConfigFromShipment` does this lookup.
3. The account's type says which carrier it is, such as FedEx.
4. The plugin finds that carrier's label service from data, then calls it.
5. The carrier service calls the carrier's API, then saves the result in trunk's route records.
6. The plugin records the call and its result.

Example: shipment 10310 is for an Acme web store order shipped FedEx Ground.
The store method points to account 10400 of type `FEDEX`.
The plugin calls `fedexRequestShipmentLabels`, and FedEx returns tracking number 794698745126.

## Records

### From trunk, used as they are

| Record | Use |
| --- | --- |
| `ShipmentGatewayConfig` | One carrier account. Its type names the carrier. |
| `ShipmentGatewayConfigType` | One row per carrier, seeded by the plugin, such as `FEDEX`. |
| `ProductStoreShipmentMeth` | Links a store, a carrier, a shipping method and a carrier account. |
| `CarrierShipmentMethod` | The carrier's own code for a shipping method, such as `FEDEX_GROUND` for `GROUND`. |
| `CustomMethod`, `CustomMethodType` | Name a service in data. Trunk already has the type `SHIP_EST` for rate services. |
| `ShipmentRouteSegment` | Carrier, method, tracking number, cost, and carrier status per route. |
| `ShipmentPackageRouteSeg` | Tracking number and label image per box. |
| `SystemProperty` | Each carrier's test and live web addresses. |

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
| `shipmentId`, `shipmentRouteSegmentId` | The route, for a label or a void |
| `contactMechId` | The address, for an address check |
| `resultSuccess` | `Y` or `N` |
| `gatewayCode`, `gatewayMessage` | The carrier's code and reason |
| `requestMessage`, `responseMessage` | The request and reply, without secrets, tokens or label images |
| `transactionDate`, `createdByUserLogin` | When, and who |

The last account check of an account is its newest `SHIP_ACCOUNT_CHECK` row here. The account record keeps no check fields.

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

Trunk's demo data has FedEx's old service codes, such as `FEDEX2DAY`. The seed above replaces them with the codes of FedEx's current API.

### Sample: one carrier account

```xml
<entity-engine-xml>
    <ShipmentGatewayConfig shipmentGatewayConfigId="10400" shipmentGatewayConfTypeId="FEDEX" description="Acme FedEx"/>
    <ShipmentGatewayFedex shipmentGatewayConfigId="10400" accountNumber="740561073" apiKey="l7a1b2c3d4e5f6"
            apiSecret="(encrypted)" testMode="Y"/>
    <ProductStoreShipmentMeth productStoreShipMethId="10401" productStoreId="ACME_WEB" shipmentMethodTypeId="GROUND"
            partyId="FEDEX" roleTypeId="CARRIER" shipmentGatewayConfigId="10400"/>
</entity-engine-xml>
```

## Services

### Shared services, called by any application

| Service | In | Does |
| --- | --- | --- |
| `checkShipmentGatewayAccount` | `shipmentGatewayConfigId` | Calls the carrier's check service. Returns `passed` and the carrier's message. |
| `checkShipmentAddress` | `contactMechId`, `shipmentGatewayConfigId` | Calls the carrier's address check service. Returns the result described below. |
| `requestShipmentLabels` | `shipmentId`, `shipmentRouteSegmentId`, `labelFormat` | Calls the carrier's label service for the route. |
| `voidShipmentLabels` | `shipmentId`, `shipmentRouteSegmentId` | Refuses a shipped shipment, then calls the carrier's void service. |

Each shared service:
1. Finds the account and its type.
2. Finds the carrier service in `ShipmentGatewayConfTypeMethod`.
3. Calls it in its own transaction, so a carrier failure does not roll back the caller's work.
4. Writes a `ShipmentGatewayResponse` row in its own transaction, so a failed call is still recorded.
5. Returns the carrier's reason as an error when the carrier refuses.

### Contracts every carrier follows

| Interface | Implemented by FedEx as |
| --- | --- |
| `shipmentGatewayAccountCheckInterface` | `fedexCheckAccount` |
| `shipmentGatewayLabelInterface` | `fedexRequestShipmentLabels` |
| `shipmentGatewayVoidInterface` | `fedexVoidShipmentLabels` |
| `shipmentGatewayAddressCheckInterface` | `fedexCheckAddress` |
| trunk's `calcShipmentEstimateInterface` | later, for rates |

### What an address check returns

| Out | Meaning |
| --- | --- |
| `addressCheckResult` | `VALID`, `INVALID` or `NOT_CHECKED` |
| `correctedAddress` | The carrier's form of the address, when it differs, as a map like a postal address |
| `carrierMessages` | The carrier's reasons, as a list |

Rules inside every address check service:
- Spaces are taken out of the postal code before the call.
- A different city spelling alone never makes the result `INVALID`.
- A carrier error or timeout returns `NOT_CHECKED`, never `INVALID`.
- The service never changes the `PostalAddress` record.

The caller decides what to do with the result. A warehouse application, for example, holds the order on `INVALID` or `NOT_CHECKED`.

The caller passes the carrier account. It is the business's address check account, which may belong to a different carrier than the order's shipping method.

### What a label service writes

Before the call, the route must have its carrier, method, origin and destination. The shipment must have its boxes.

```xml
<entity-engine-xml>
    <ShipmentRouteSegment shipmentId="10310" shipmentRouteSegmentId="00001" carrierPartyId="FEDEX" shipmentMethodTypeId="GROUND"
            trackingIdNumber="794698745126" carrierServiceStatusId="SHRSCS_ACCEPTED" actualCost="9.85" currencyUomId="USD"/>
    <ShipmentPackageRouteSeg shipmentId="10310" shipmentPackageSeqId="00001" shipmentRouteSegmentId="00001"
            trackingCode="794698745126" labelImage="(label bytes)"/>
    <ShipmentGatewayResponse shipmentGatewayResponseId="10500" shipmentGatewayConfigId="10400" customMethodTypeId="SHIP_LABEL"
            shipmentId="10310" shipmentRouteSegmentId="00001" resultSuccess="Y" transactionDate="2026-11-12 09:05:00.0"/>
</entity-engine-xml>
```

The route moves from `SHRSCS_NOT_STARTED` through `SHRSCS_CONFIRMED` to `SHRSCS_ACCEPTED`, using trunk's status changes.
A void moves it to `SHRSCS_VOIDED`.

## Inside the FedEx package

- All FedEx code sits in one package, `co.hotwax.shippinggateway.fedex`. The next carrier gets its own package.
- FedEx uses an OAuth login: the API key and secret buy a token that lasts about an hour. The token is kept in an OFBiz cache per account until it expires.
- Every HTTP call goes through one small FedEx client class. It uses the HTTP client built into Java, so no extra library is needed.
- Requests are built from the route's records: the origin facility's address, the customer's address, each box's weight and size, and the carrier service code.
- Labels come back as base64 text, in PNG, PDF or ZPL format.
- The address check uses FedEx's address validation API. FedEx returns results in the order the addresses were sent, and does not echo back a reference id.
- The address result comes from FedEx's resolved address and its attributes. The postal code is read from the postal code token, since the resolved address has no plain postal code field. These come from fixes in earlier HotWax FedEx work.

## Automatic void

A service ECA on `updateShipment` calls `voidShipmentLabels` when a shipment with accepted labels moves to cancelled.
The void runs after the shipment change commits, so a carrier error never undoes the cancel. A failed void is in the call log.

## Security

- Services need the plugin's `SHIPPINGGATEWAY` permission.
- The secret is encrypted and never returned, logged or searched by.
- Nothing in the plugin knows about the owner of an account. An application that needs owners, such as a warehouse serving many merchants, adds that itself.

## Tests

- Automatic tests replace the FedEx client with one that returns saved FedEx replies. They never call FedEx.
- Saved replies use made-up accounts and addresses.
- A person tests against FedEx's test system by hand before each release.

## Decisions

These settle the points that were open for the design review. Each one can change in review, and the change goes in this section.

1. Language: all services, shared and carrier, are written in Groovy. Carrier calls need HTTP and JSON, and one language keeps the plugin simple.
2. Route set-up: the plugin makes the route if the shipment has none. It takes the carrier and method from the order's ship group, the origin from the facility, and the destination from the ship group's address. A caller that already made a route keeps it.
3. Call log size: request and reply text is kept up to 64 KB each. Longer text is cut, and the row says it was cut. Label images are never kept in the log.
4. Route status: FedEx makes the label in one call, so the route moves through `SHRSCS_CONFIRMED` to `SHRSCS_ACCEPTED` in the same transaction.
5. Residential flag: it comes from the last address check of the destination. `RESIDENTIAL` and `MIXED` mean residential. `BUSINESS` means not. With no check, or `UNKNOWN`, the shipment counts as residential, since most merchant orders go to homes. This choice needs a check with FedEx billing rules in issue #13.
6. Pickup type: kept on the FedEx account record, so each account says how its boxes leave the warehouse.
7. Label stock: `STOCK_4X6` for ZPL, `PAPER_4X6` for PDF and PNG, as earlier FedEx work found for thermal printers.
