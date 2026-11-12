# FedEx API reference

What the FedEx package calls, and how each packet field fills a FedEx field.
The package reads only the packet and its own account record. It never reads shipment, address or order records.
The source for every field is FedEx's published REST API specs. Check a field against FedEx's developer portal before relying on it.

## Addresses and login

| Environment | Address |
| --- | --- |
| Test | `https://apis-sandbox.fedex.com` |
| Live | `https://apis.fedex.com` |

The address comes from the `SystemProperty` rows `fedex.url.test` and `fedex.url.live`. The account's `testMode` picks one.

Login is OAuth client credentials:

```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id={apiKey}&client_secret={apiSecret}
```

The reply has `access_token` and `expires_in`, about 3600 seconds.
Every other call sends `Authorization: Bearer {access_token}` and `Content-Type: application/json`.

Token rules:
- Keep one token per carrier account, in an OFBiz cache keyed by `shipmentGatewayConfigId`.
- Let the cached token expire 5 minutes before FedEx's expiry.
- Clear the token when the account changes.
- On a 401 reply, get a new token once and repeat the call once.

## Calls

| Operation | Method and path | Plugin service |
| --- | --- | --- |
| Account check | `POST /rate/v1/rates/quotes` | `fedexCheckAccount` |
| Address check | `POST /address/v1/addresses/resolve` | `fedexCheckAddress` |
| Label request | `POST /ship/v1/shipments` | `fedexRequestShipmentLabels` |
| Void | `PUT /ship/v1/shipments/cancel` | `fedexVoidShipmentLabels` |

FedEx has no "check my account" call. A rate quote needs the account number, so a small quote between two of the facility's own addresses proves the login and the account.

## Label request

`POST /ship/v1/shipments`

All boxes of one shipment go in one request. FedEx returns one master tracking number and one tracking number per box.

### Request fields and where they come from

| FedEx field | Required | Source |
| --- | --- | --- |
| `accountNumber.value` | Yes | `ShipmentGatewayFedex.accountNumber`, for the packet's `shipmentGatewayConfigId` |
| `labelResponseOptions` | Yes | Always `LABEL`, so the label comes back in the reply |
| `requestedShipment.shipDatestamp` | No | Packet `shipDate` |
| `requestedShipment.shipper.contact.companyName` | Yes | Packet `shipper.companyName` |
| `requestedShipment.shipper.contact.personName` | No | Packet `shipper.personName` |
| `requestedShipment.shipper.contact.phoneNumber` | Yes | Packet `shipper.phoneNumber` |
| `requestedShipment.shipper.address.streetLines` | Yes | Packet `shipper.address.streetLines` |
| `requestedShipment.shipper.address.city` | Yes | Packet `shipper.address.city` |
| `requestedShipment.shipper.address.stateOrProvinceCode` | US, CA | Packet `shipper.address.stateOrProvinceCode` |
| `requestedShipment.shipper.address.postalCode` | US, CA | Packet `shipper.address.postalCode` |
| `requestedShipment.shipper.address.countryCode` | Yes | Packet `shipper.address.countryCode` |
| `requestedShipment.recipients[0].contact.*` | Yes | Packet `recipient` names, phone and email |
| `requestedShipment.recipients[0].address.*` | Yes | Packet `recipient.address`, as for the shipper |
| `requestedShipment.recipients[0].address.residential` | No | Packet `recipient.address.residential` |
| `requestedShipment.serviceType` | Yes | Packet `serviceCode` |
| `requestedShipment.packagingType` | Yes | `YOUR_PACKAGING` for now |
| `requestedShipment.pickupType` | Yes | `ShipmentGatewayFedex.pickupType` |
| `requestedShipment.shippingChargesPayment.paymentType` | Yes | `SENDER`, billed to the account |
| `requestedShipment.labelSpecification.imageType` | Yes | Packet `labelFormat`: `PDF`, `PNG`, or `ZPLII` for `ZPL` |
| `requestedShipment.labelSpecification.labelStockType` | Yes | `STOCK_4X6` for ZPL, `PAPER_4X6` for PDF and PNG |
| `requestedShipment.totalWeight` | Yes | Sum of the packages' `weight` |
| `requestedShipment.totalPackageCount` | No | Number of packages |
| `requestedShipment.requestedPackageLineItems[i].sequenceNumber` | No | Position of the package, from 1 |
| `requestedShipment.requestedPackageLineItems[i].weight` | Yes | Package `weight` and `weightUnit` (`LB` or `KG`) |
| `requestedShipment.requestedPackageLineItems[i].dimensions` | No | Package `length`, `width`, `height` and `dimensionUnit` (`IN` or `CM`), when given |
| `requestedShipment.requestedPackageLineItems[i].customerReferences` | No | `CUSTOMER_REFERENCE` = packet `references.externalOrderId`, else `references.orderId` |

A packet missing a required field fails before the call, with the field's name in the reason.

Values FedEx accepts, from its spec:

| Field | Values |
| --- | --- |
| `imageType` | `ZPLII`, `EPL2`, `PDF`, `PNG` |
| `pickupType` | `USE_SCHEDULED_PICKUP`, `DROPOFF_AT_FEDEX_LOCATION`, `CONTACT_FEDEX_TO_SCHEDULE` |
| `paymentType` | `SENDER`, `RECIPIENT`, `THIRD_PARTY`, `COLLECT` |
| `weight.units` | `LB`, `KG` |
| `dimensions.units` | `IN`, `CM` |

### Reply fields and where they go

| FedEx field | Result packet field |
| --- | --- |
| `output.transactionShipments[0].masterTrackingNumber` | `masterTrackingNumber` |
| `output.transactionShipments[0].pieceResponses[i].trackingNumber` | `packages[i].trackingNumber`, with the package's `packageId`, matched by `packageSequenceNumber` |
| `...pieceResponses[i].packageDocuments[j].encodedLabel` | `packages[i].labelContent`, base64 as received |
| `...packageDocuments[j].url` | Only if `encodedLabel` is missing: download the label from it |
| `output.transactionShipments[0].completedShipmentDetail.shipmentRating.shipmentRateDetails[0].totalNetCharge` | `totalCharge` and `currencyCode` |
| `output.transactionShipments[0].alerts` and `...packageDocuments[j].alerts` | `warnings` |

The caller saves the result in its own records.

An alert of type `NOTE` or `WARNING` is not a failure. Example: `SHIP.RECIPIENT.POSTALCITY.MISMATCH` comes with a valid label. Record it, and keep the label.

## Void

`PUT /ship/v1/shipments/cancel`

| FedEx field | Source |
| --- | --- |
| `accountNumber.value` | `ShipmentGatewayFedex.accountNumber` |
| `trackingNumber` | Packet `masterTrackingNumber` |
| `deletionControl` | `DELETE_ALL_PACKAGES` |

The void worked only if `output.cancelledShipment` is `true`. A 200 reply with `false` is a failure. Its reason is in `output.message` or `output.alerts`.

## Address check

`POST /address/v1/addresses/resolve`

| FedEx field | Source |
| --- | --- |
| `addressesToValidate[0].address.streetLines` | Packet `address.streetLines` |
| `addressesToValidate[0].address.city` | Packet `address.city` |
| `addressesToValidate[0].address.stateOrProvinceCode` | Packet `address.stateOrProvinceCode` |
| `addressesToValidate[0].address.postalCode` | Packet `address.postalCode`, with spaces taken out |
| `addressesToValidate[0].address.countryCode` | Packet `address.countryCode` |

Reading the reply, from fixes in earlier FedEx work:
- Results come back in the order the addresses were sent. FedEx does not echo a reference id.
- The postal code is in `postalCodeToken.value`. The resolved address has no plain postal code field.
- The street is in `streetLinesToken`.
- Attribute values come as `true`, `false` or their text forms. Treat both alike.
- `classification` gives `BUSINESS`, `RESIDENTIAL`, `MIXED` or `UNKNOWN`. Return it, so the caller can set `residential` on later labels.

Result rules, from the requirements:
- `VALID` when FedEx resolves the address and its delivery point.
- `INVALID` when it cannot. A different city spelling alone is never `INVALID`.
- `NOT_CHECKED` on any HTTP error, timeout or 5xx reply.

## Errors

A failed call returns an HTTP 4xx or 5xx code and a body like:

```json
{ "transactionId": "...", "errors": [ { "code": "SHIPMENT.POSTALCODE.INVALID", "message": "..." } ] }
```

| Reply | What the plugin does |
| --- | --- |
| 400 with `errors` | Fail the call. Return FedEx's first `code` and `message` as the reason |
| 401 | Get a new token once, then repeat once. Fail if it happens again |
| 403 | Fail. The account or key lacks access to this API |
| 429 or 503 | Fail this try, and try again later with a growing wait |
| 500 | Fail this try, and try again later |

Every call, failed or not, writes a `ShipmentGatewayResponse` row without the token, the secret or label images.

## Service codes

The plugin seeds these `CarrierShipmentMethod` rows for FedEx, from FedEx's service list. The caller reads them to fill the packet's `serviceCode`:

| `shipmentMethodTypeId` | `carrierServiceCode` | Where |
| --- | --- | --- |
| `GROUND` | `FEDEX_GROUND` | US, business addresses |
| `GROUND_HOME` | `GROUND_HOME_DELIVERY` | US, residential addresses |
| `EXPRESS_SAVER` | `FEDEX_EXPRESS_SAVER` | US |
| `SECOND_DAY` | `FEDEX_2_DAY` | US |
| `SECOND_DAY_AM` | `FEDEX_2_DAY_AM` | US |
| `ST_OVERNIGHT` | `STANDARD_OVERNIGHT` | US |
| `PR_OVERNIGHT` | `PRIORITY_OVERNIGHT` | US |
| `FI_OVERNIGHT` | `FIRST_OVERNIGHT` | US |
| `INTERNAT_ECON` | `INTERNATIONAL_ECONOMY` | International, including Canada |
| `INTERNAT_PRI` | `FEDEX_INTERNATIONAL_PRIORITY` | International, including Canada |

The method type ids are trunk's own. Trunk has them only in demo and setup data, so the plugin seeds the ones it uses.
Trunk's demo data also has older FedEx codes, such as `FEDEX2DAY`. The plugin's seed replaces them.
International shipments also need customs details. They are out of scope for the first release.

## Sample replies

Test replies live in `src/test/resources/fedex/`, one file per scenario. They follow FedEx's published spec shapes, with made-up data. Replace each with a real test-system reply in issue #13, after taking out account numbers.
