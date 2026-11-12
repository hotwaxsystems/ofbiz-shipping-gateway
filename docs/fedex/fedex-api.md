# FedEx API reference

What the FedEx package calls, and how OFBiz records fill each FedEx field.
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

| FedEx field | Required | OFBiz source |
| --- | --- | --- |
| `accountNumber.value` | Yes | `ShipmentGatewayFedex.accountNumber` |
| `labelResponseOptions` | Yes | Always `LABEL`, so the label comes back in the reply |
| `requestedShipment.shipDatestamp` | No | Today in the facility's time zone, `YYYY-MM-DD` |
| `requestedShipment.shipper.contact.companyName` | Yes | Name of `Shipment.partyIdFrom`, from `PartyGroup.groupName` |
| `requestedShipment.shipper.contact.phoneNumber` | Yes | `ShipmentRouteSegment.originTelecomNumberId` |
| `requestedShipment.shipper.address.streetLines` | Yes | Origin `PostalAddress.address1`, `address2` |
| `requestedShipment.shipper.address.city` | Yes | Origin `PostalAddress.city` |
| `requestedShipment.shipper.address.stateOrProvinceCode` | US, CA | `Geo.geoCode` of `stateProvinceGeoId`, such as `UT` |
| `requestedShipment.shipper.address.postalCode` | US, CA | Origin `PostalAddress.postalCode` |
| `requestedShipment.shipper.address.countryCode` | Yes | `Geo.geoCode` of `countryGeoId`, such as `US` for `USA` |
| `requestedShipment.recipients[0].contact.personName` | Yes | Destination `PostalAddress.toName`, else `attnName` |
| `requestedShipment.recipients[0].contact.phoneNumber` | Yes | `ShipmentRouteSegment.destTelecomNumberId` |
| `requestedShipment.recipients[0].address.*` | Yes | Destination `PostalAddress`, as for the shipper |
| `requestedShipment.recipients[0].address.residential` | No | From the last address check. See the decisions below |
| `requestedShipment.serviceType` | Yes | `CarrierShipmentMethod.carrierServiceCode` for FedEx and the route's `shipmentMethodTypeId` |
| `requestedShipment.packagingType` | Yes | `YOUR_PACKAGING` for now |
| `requestedShipment.pickupType` | Yes | `ShipmentGatewayFedex.pickupType` |
| `requestedShipment.shippingChargesPayment.paymentType` | Yes | `SENDER`, billed to the account |
| `requestedShipment.labelSpecification.imageType` | Yes | The call's `labelFormat`: `PDF`, `PNG` or `ZPLII` |
| `requestedShipment.labelSpecification.labelStockType` | Yes | `STOCK_4X6` for `ZPLII`, `PAPER_4X6` for `PDF` and `PNG` |
| `requestedShipment.totalWeight` | Yes | Sum of `ShipmentPackage.weight` |
| `requestedShipment.totalPackageCount` | No | Number of `ShipmentPackage` rows |
| `requestedShipment.requestedPackageLineItems[i].sequenceNumber` | No | Position of the package, from 1 |
| `requestedShipment.requestedPackageLineItems[i].weight.units` | Yes | `LB` for `WT_lb`, `KG` for `WT_kg` |
| `requestedShipment.requestedPackageLineItems[i].weight.value` | Yes | `ShipmentPackage.weight` |
| `requestedShipment.requestedPackageLineItems[i].dimensions` | No | `ShipmentPackage.boxLength`, `boxWidth`, `boxHeight`, else the `ShipmentBoxType`'s. `IN` or `CM` from `dimensionUomId` |
| `requestedShipment.requestedPackageLineItems[i].customerReferences` | No | `CUSTOMER_REFERENCE` = the order's `externalId`, else `orderId` |

Values FedEx accepts, from its spec:

| Field | Values |
| --- | --- |
| `imageType` | `ZPLII`, `EPL2`, `PDF`, `PNG` |
| `pickupType` | `USE_SCHEDULED_PICKUP`, `DROPOFF_AT_FEDEX_LOCATION`, `CONTACT_FEDEX_TO_SCHEDULE` |
| `paymentType` | `SENDER`, `RECIPIENT`, `THIRD_PARTY`, `COLLECT` |
| `weight.units` | `LB`, `KG` |
| `dimensions.units` | `IN`, `CM` |

### Reply fields and where they go

| FedEx field | OFBiz target |
| --- | --- |
| `output.transactionShipments[0].masterTrackingNumber` | `ShipmentRouteSegment.trackingIdNumber` |
| `output.transactionShipments[0].pieceResponses[i].trackingNumber` | `ShipmentPackageRouteSeg.trackingCode`, matched by `packageSequenceNumber` |
| `output.transactionShipments[0].pieceResponses[i].packageDocuments[j].encodedLabel` | `ShipmentPackageRouteSeg.labelImage`, decoded from base64 |
| `...packageDocuments[j].url` | Only if `encodedLabel` is missing: download the label from it |
| `output.transactionShipments[0].completedShipmentDetail.shipmentRating.shipmentRateDetails[0].totalNetCharge` | `ShipmentRouteSegment.actualCost` |
| `output.transactionShipments[0].alerts` and `...packageDocuments[j].alerts` | The call log, as warnings |

After a reply with labels, the route moves to `SHRSCS_ACCEPTED`.

An alert of type `NOTE` or `WARNING` is not a failure. Example: `SHIP.RECIPIENT.POSTALCITY.MISMATCH` comes with a valid label. Record it, and keep the label.

## Void

`PUT /ship/v1/shipments/cancel`

| FedEx field | OFBiz source |
| --- | --- |
| `accountNumber.value` | `ShipmentGatewayFedex.accountNumber` |
| `trackingNumber` | `ShipmentRouteSegment.trackingIdNumber`, the master tracking number |
| `deletionControl` | `DELETE_ALL_PACKAGES` |

The void worked only if `output.cancelledShipment` is `true`. A 200 reply with `false` is a failure. Its reason is in `output.message` or `output.alerts`.
After a void, the route moves to `SHRSCS_VOIDED`.

## Address check

`POST /address/v1/addresses/resolve`

| FedEx field | OFBiz source |
| --- | --- |
| `addressesToValidate[0].address.streetLines` | `PostalAddress.address1`, `address2` |
| `addressesToValidate[0].address.city` | `PostalAddress.city` |
| `addressesToValidate[0].address.stateOrProvinceCode` | `Geo.geoCode` of `stateProvinceGeoId` |
| `addressesToValidate[0].address.postalCode` | `PostalAddress.postalCode`, with spaces taken out |
| `addressesToValidate[0].address.countryCode` | `Geo.geoCode` of `countryGeoId` |

Reading the reply, from fixes in earlier FedEx work:
- Results come back in the order the addresses were sent. FedEx does not echo a reference id.
- The postal code is in `postalCodeToken.value`. The resolved address has no plain postal code field.
- The street is in `streetLinesToken`.
- Attribute values come as `true`, `false` or their text forms. Treat both alike.
- `classification` gives `BUSINESS`, `RESIDENTIAL`, `MIXED` or `UNKNOWN`. Keep it for the label's `residential` flag.

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

The plugin seeds these `CarrierShipmentMethod` rows for FedEx, from FedEx's service list:

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
