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

| FedEx field | Required | Packet field |
| --- | --- | --- |
| `accountNumber.value` | Yes | `ShipmentGatewayFedex.accountNumber`, for the packet's `shipmentGatewayConfigId` |
| `labelResponseOptions` | Yes | Always `LABEL`, so the label comes back in the reply |
| `requestedShipment.shipDatestamp` | No | `dateOfSale` |
| `requestedShipment.shipper.contact.companyName` | Yes | `companyAddress.companyName` |
| `requestedShipment.shipper.contact.personName` | No | `companyAddress.toName` |
| `requestedShipment.shipper.contact.phoneNumber` | Yes | `originAddress.phoneAreaCode` + `phoneNumber` |
| `requestedShipment.shipper.address.*` | Yes | `originAddress`: `address1` and `address2` as `streetLines`, `city`, `stateOrProvinceCode`, `postalCode`, `countryCode` |
| `requestedShipment.recipients[0].contact.personName` | Yes | `destAddress.toName` |
| `requestedShipment.recipients[0].contact.companyName` | No | `destAddress.companyName` |
| `requestedShipment.recipients[0].contact.phoneNumber` | Yes | `destAddress.phoneAreaCode` + `phoneNumber` |
| `requestedShipment.recipients[0].contact.emailAddress` | No | `destAddress.emailAddress` |
| `requestedShipment.recipients[0].address.*` | Yes | `destAddress`, as for the shipper |
| `requestedShipment.recipients[0].address.residential` | No | `destAddress.residential` |
| `requestedShipment.serviceType` | Yes | `shipmentMethod` |
| `requestedShipment.packagingType` | Yes | `packagingType` |
| `requestedShipment.pickupType` | Yes | `pickupType` |
| `requestedShipment.shippingChargesPayment.paymentType` | Yes | `SENDER`. `THIRD_PARTY` with `payorAddress` and `billingAccountNbr` comes later |
| `requestedShipment.labelSpecification.imageType` | Yes | `labelImageType` |
| `requestedShipment.labelSpecification.labelStockType` | Yes | `labelSize` |
| `requestedShipment.totalWeight` | Yes | Sum of the parcels' `weightValue` |
| `requestedShipment.totalPackageCount` | No | Number of parcels |
| `requestedShipment.requestedPackageLineItems[i].sequenceNumber` | No | Position of the parcel, from 1 |
| `requestedShipment.requestedPackageLineItems[i].weight` | Yes | Parcel `weightValue` and `weightUnit` (`LB` or `KG`) |
| `requestedShipment.requestedPackageLineItems[i].dimensions` | No | Parcel `length`, `width`, `height` and `dimensionUnit` (`IN` or `CM`), when given |
| `requestedShipment.requestedPackageLineItems[i].customerReferences` | No | `CUSTOMER_REFERENCE` = `orderName`, else `primaryOrderId` |

The packet uses unigate's field names, shared by every carrier. A packet missing a required field fails before the call, with the field's name in the reason.

Values FedEx accepts, from its spec:

| Field | Values |
| --- | --- |
| `imageType` | `ZPLII`, `EPL2`, `PDF`, `PNG` |
| `pickupType` | `USE_SCHEDULED_PICKUP`, `DROPOFF_AT_FEDEX_LOCATION`, `CONTACT_FEDEX_TO_SCHEDULE` |
| `paymentType` | `SENDER`, `RECIPIENT`, `THIRD_PARTY`, `COLLECT` |
| `weight.units` | `LB`, `KG` |
| `dimensions.units` | `IN`, `CM` |

### Reply fields and where they go

| FedEx field | Result field |
| --- | --- |
| `output.transactionShipments[0].masterTrackingNumber` | `shippingLabelMap.referenceNumber` |
| `output.transactionShipments[0].pieceResponses[i].trackingNumber` | `shippingLabelMap.packages[i].trackingIdNumber`, with the parcel's `shipmentPackageSeqId`, matched by `packageSequenceNumber` |
| `...pieceResponses[i].packageDocuments[j].encodedLabel` | `shippingLabelMap.packages[i].imageBytes`, base64 as received |
| `...packageDocuments[j].url` | `shippingLabelMap.packages[i].labelImageUrl` |
| `output.transactionShipments[0].completedShipmentDetail.shipmentRating.shipmentRateDetails[0].totalNetCharge` | `shippingLabelMap.shippingCharge` and `currencyUomId` |
| `output.transactionShipments[0].alerts` and `...packageDocuments[j].alerts` | `warningMessages` |
| always | `shippingLabelMap.actualCarrierCode` = `FEDEX` |

The caller saves the result in its own records.

An alert of type `NOTE` or `WARNING` is not a failure. Example: `SHIP.RECIPIENT.POSTALCITY.MISMATCH` comes with a valid label. Record it, and keep the label.

## Void

`PUT /ship/v1/shipments/cancel`

| FedEx field | Source |
| --- | --- |
| `accountNumber.value` | `ShipmentGatewayFedex.accountNumber` |
| `trackingNumber` | `trackingNumber`, the master tracking number |
| `deletionControl` | `DELETE_ALL_PACKAGES` |

The void worked only if `output.cancelledShipment` is `true`. A 200 reply with `false` is a failure. Its reason is in `output.message` or `output.alerts`.

## Address check

`POST /address/v1/addresses/resolve`

Each entry of the packet's `addresses` becomes one `addressesToValidate` entry, in the same order.

| FedEx field | Packet field |
| --- | --- |
| `addressesToValidate[i].address.streetLines` | `addresses[i].address.address1`, `address2` |
| `addressesToValidate[i].address.city` | `addresses[i].address.city` |
| `addressesToValidate[i].address.stateOrProvinceCode` | `addresses[i].address.stateOrProvinceCode` |
| `addressesToValidate[i].address.postalCode` | `addresses[i].address.postalCode`, with spaces taken out |
| `addressesToValidate[i].address.countryCode` | `addresses[i].address.countryCode` |

Reading the reply, from fixes in earlier FedEx work:
- Results come back in the order the addresses were sent. FedEx does not echo a reference id, so take each `clientReferenceId` from the packet by position.
- The postal code is in `postalCodeToken.value`. The resolved address has no plain postal code field.
- The street is in `streetLinesToken`.
- Attribute values come as `true`, `false` or their text forms. Treat both alike.
- `classification` gives `BUSINESS`, `RESIDENTIAL`, `MIXED` or `UNKNOWN`. Return it in `validationSummary`, so the caller can set `residential` on later labels.

Result rules, from the requirements:
- `isValid` true when FedEx resolves the address and its delivery point.
- `isValid` false when it cannot. A different city spelling alone never makes it false.
- `success` false on any HTTP error, timeout or 5xx reply. The caller treats that as not checked.

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

The plugin seeds these `CarrierShipmentMethod` rows for FedEx, from FedEx's service list. The caller reads them to fill the packet's `shipmentMethod`:

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
