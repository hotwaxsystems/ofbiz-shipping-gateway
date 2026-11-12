# How tests use the sample files

The automatic tests check the plugin without calling any carrier.
They use two folders of sample files, both with made-up data:

| Folder | What it holds |
| --- | --- |
| `src/test/resources/packets/` | Packets a caller sends to the plugin, and the results it should get back |
| `src/test/resources/fedex/` | Replies FedEx would send, one per situation |

## The idea

Every carrier call goes through one small carrier client, such as the FedEx client.
In real use, the client calls the carrier over the internet.
In a test, the test kit swaps in a fake client. The fake never goes online. It answers with the sample reply the test chose.

The plugin's own code does not know which client it has. So the test runs the real plugin code, from the packet to the result.

## One test, in four steps

1. Read a request packet from `packets/`.
2. Tell the fake client which reply file to give for which carrier call.
3. Call the plugin's real shared service with the packet.
4. Check the result, and the call log row.

```groovy
def packet = readJson("packets/label-request-two-boxes.json")
fakeCarrierClient.answer("POST", "/ship/v1/shipments", "fedex/ship-two-boxes.json")
def result = runService("requestShipmentLabels", packet)
assert result.success
assert result.shippingLabelMap.referenceNumber == "794600000001"
assert result.shippingLabelMap.packages*.shipmentPackageSeqId == ["00001", "00002"]
assert result.shippingLabelMap.packages.every { it.trackingIdNumber && it.imageBytes }
```

`readJson` and `fakeCarrierClient` come from the test kit, built in issue #6.
The fake also checks the request the plugin sent. A test can assert, for example, that FedEx's `serviceType` was `FEDEX_GROUND`.

## Which files go together

| Packet | Carrier reply | What the test checks |
| --- | --- | --- |
| `label-request-one-box.json` | `fedex/ship-one-box.json` | One package, with its box id, tracking number and label |
| `label-request-two-boxes.json` | `fedex/ship-two-boxes.json` | A master tracking number, and one per box |
| `label-request-one-box.json` | `fedex/ship-with-note-alert.json` | Success, with the note in `warningMessages` |
| `label-request-one-box.json` | `fedex/ship-invalid-postal-code.json` | `success` false, with FedEx's reason |
| `void-request.json` | `fedex/cancel-ok.json` | Success |
| `void-request.json` | `fedex/cancel-not-cancelled.json` | `success` false, even though FedEx answered 200 |
| `address-check-request.json` | `fedex/address-good.json` | `isValid` true, as in `address-check-result-good.json` |
| `address-check-request.json` | `fedex/address-undeliverable.json` | `isValid` false, with the issue |
| `address-check-request.json` | `fedex/address-city-spelled-differently.json` | `isValid` true |
| any request | `fedex/token-wrong-secret.json` for the login | `success` false, with FedEx's reason |
| an account check | `fedex/account-check-ok.json` or `account-check-wrong-account.json` | Passed, or failed with FedEx's reason |

Each FedEx issue's done checklist names the files its tests use.

## Running the tests

From the OFBiz checkout:

```
./gradlew "ofbiz --test component=shippinggateway"
```

No carrier account, internet or shipment records are needed.

## When real replies arrive

Issue #13 tests against FedEx's own test system. Its real replies replace the sample files of the same name, after account numbers and keys are taken out.
The tests stay the same. They then check the plugin against real FedEx answers.

## Adding a carrier

A new carrier, such as UPS, adds its own folder, `src/test/resources/ups/`, with one reply file per situation.
It reuses the same packets, since packets are the same for every carrier.
