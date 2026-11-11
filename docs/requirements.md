# Shipping gateway requirements

## Scope

This plugin lets an OFBiz business get shipping labels from its own carrier accounts.
It covers carrier accounts, labels and voided labels. FedEx is the first carrier.

A shipping gateway is the code that talks to a carrier's web API.
A carrier account is the business's account with a carrier. The carrier bills that account for each label.

## Actors

- **Shipping Admin** sets up carrier accounts and links them to store shipping methods.
- **Packer** packs a shipment and puts the label on each box.
- **Warehouse Manager** voids a wrong label.
- **Carrier** (FedEx first) checks the account, makes the label and gives the tracking number.
- **Developer** adds a new carrier to the plugin.

## Business process story

### Set up a carrier account

1. Shipping Admin records a carrier account: the carrier, the account number, the API key and secret, and test or live mode.
2. Company keeps the secret safe. Nobody can read it back after it is saved.
3. Company checks the account with the carrier and records the result.
4. Shipping Admin links the account to the store shipping methods that use it, such as FedEx Ground in the web store.
5. Shipping Admin switches the account to live mode when testing is done. Company checks the account again.

### Get a label

6. Packer completes packing a shipment that has one or more boxes.
7. Company asks the carrier for a label for each box. It uses the account linked to the order's store shipping method.
8. Carrier returns a label and a tracking number for each box.
9. Company records the tracking numbers, the labels and the cost on the shipment. The labels print at the packing station.
10. If the carrier refuses, Company shows the carrier's reason. The shipment has no label until the problem is fixed.

### Void a label

11. Warehouse Manager voids the labels of a shipment that has not shipped.
12. Company asks the carrier to cancel them and records the labels as voided.

### Add a carrier

13. Developer adds a new carrier by following the same shared steps: check an account, get labels, void labels.
14. Shipping Admin can then set up an account for the new carrier with no other change to OFBiz.

## Rules

- Each store shipping method uses one carrier account.
- A secret is never shown again after it is saved. It can only be replaced.
- A test-mode label is never used to ship.
- A label is voided only before the shipment ships.
- Every call to a carrier is recorded with its time, its user and its result. Secrets are never recorded.
- One carrier's failure never stops another carrier's labels.
- A carrier with no account set up does nothing.

## Scenarios

| # | Scenario | Example | Outcome |
| --- | --- | --- | --- |
| 1 | Add an account | Acme Outdoor adds FedEx account 740561073 in test mode | Account saved. Check passed |
| 2 | Wrong secret | The secret is typed wrong | Account saved. Check failed, with FedEx's reason |
| 3 | Link a method | FedEx Ground in the Acme web store is linked to the account | Its orders use this account for labels |
| 4 | One box | Shipment 10310, FedEx Ground, one box of 2.4 lb | One label and tracking number |
| 5 | Two boxes | Shipment 10320 has two boxes | Two labels, one tracking number for each box |
| 6 | Carrier refuses | The postal code does not match the city | No label. FedEx's reason shown |
| 7 | Void | The packer used the wrong box | Labels cancelled at FedEx and marked voided |
| 8 | Void after shipping | Shipment 10310 has already shipped | Void refused |
| 9 | No account linked | The order's method is UPS Ground, with no UPS account | No label. The reason says no account is linked |
| 10 | Go live | Acme switches its account to live mode | Check passed. Next labels are real |

## Out of scope for now

- Live shipping rates at checkout, and choosing the cheapest service.
- Checking an address with the carrier.
- Tracking updates after the label is made.
- Third-party billing.
- Customs papers for international shipments.
- Carriers other than FedEx.

## Open question

- Can one business have two accounts with the same carrier, such as one per store?
