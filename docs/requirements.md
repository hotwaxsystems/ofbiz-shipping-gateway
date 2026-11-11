# Shipping gateway requirements

## Scope

This plugin lets an OFBiz business use its own carrier accounts.
It covers carrier accounts, ship-to address checks, labels and voided labels. FedEx is the first carrier.

A shipping gateway is the code that talks to a carrier's web API.
A carrier account is the business's account with a carrier. The carrier bills that account for each label.

## Actors

- **Shipping Admin** sets up carrier accounts and links them to store shipping methods.
- **Customer Service** fixes a ship-to address that failed its check.
- **Packer** packs a shipment and puts the label on each box.
- **Warehouse Manager** voids a wrong label.
- **Carrier** (FedEx first) checks the account and the address, makes the label and gives the tracking number.
- **Developer** adds a new carrier to the plugin.

## Business process story

### Set up a carrier account

1. Shipping Admin records a carrier account: the carrier, the account number, the API key and secret, and test or live mode.
2. Company keeps the secret safe. Nobody can read it back after it is saved.
3. Company checks the account with the carrier and records the result.
4. Shipping Admin links the account to the store shipping methods that use it, such as FedEx Ground in the web store.
5. Shipping Admin switches the account to live mode when testing is done. Company checks the account again.

### Check a ship-to address

6. Company checks an order's ship-to address with the carrier when the order is recorded. It uses the account of the order's store shipping method.
7. Carrier answers that the address is good, that it cannot be delivered, or that it has a corrected form.
8. Company records the answer on the order.
9. If the address cannot be delivered, Company holds the order from picking.
10. If the carrier cannot be reached, Company records that the address was not checked, and holds the order.
11. Customer Service fixes the address. Company checks it again.
12. Company checks held addresses again on a schedule, and releases an order when its address now passes.

### Get a label

13. Packer completes packing a shipment that has one or more boxes.
14. Company asks the carrier for a label for each box. It uses the account linked to the order's store shipping method.
15. Carrier returns a label and a tracking number for each box.
16. Company records the tracking numbers, the labels and the cost on the shipment. The labels print at the packing station.
17. If the carrier refuses, Company shows the carrier's reason. The shipment has no label until the problem is fixed.

### Void a label

18. Warehouse Manager voids the labels of a shipment that has not shipped.
19. Company asks the carrier to cancel them and records the labels as voided.
20. Company also voids the labels when a shipment with labels is cancelled.

### Add a carrier

21. Developer adds a new carrier by following the same shared steps: check an account, check an address, get labels, void labels.
22. Shipping Admin can then set up an account for the new carrier with no other change to OFBiz.

## Rules

- Each store shipping method uses one carrier account.
- A secret is never shown again after it is saved. It can only be replaced.
- An address check never changes the order's address by itself. A corrected form is shown, and a person decides.
- A different spelling of the city alone never fails an address, such as "Port St. Lucie" and "Port Saint Lucie".
- Spaces are taken out of a postal code before the check, such as `84601- 1234` to `84601-1234`.
- "Not checked" is recorded apart from "cannot be delivered", so a carrier outage is never taken for a bad address.
- A test-mode label is never used to ship.
- A label is voided only before the shipment ships.
- Every call to a carrier is recorded with its time, its user and its result. Secrets are never recorded.
- One carrier's failure never stops another carrier's work.
- A carrier with no account set up does nothing.

## Scenarios

| # | Scenario | Example | Outcome |
| --- | --- | --- | --- |
| 1 | Add an account | Acme Outdoor adds FedEx account 740561073 in test mode | Account saved. Check passed |
| 2 | Wrong secret | The secret is typed wrong | Account saved. Check failed, with FedEx's reason |
| 3 | Link a method | FedEx Ground in the Acme web store is linked to the account | Its orders use this account |
| 4 | Good address | Order 1050 ships to 100 Main St, Provo, UT 84601 | Address passes. Order goes on |
| 5 | Bad address | Order 1051 ships to postal code 84199 with the city Provo | Cannot be delivered. Order held |
| 6 | City spelled differently | Order 1052 ships to Port St. Lucie. FedEx says Port Saint Lucie | Address passes |
| 7 | Corrected form | FedEx adds the ZIP+4 to order 1053's postal code | Address passes. The corrected form is recorded |
| 8 | Carrier down | FedEx does not answer for order 1054 | Not checked. Order held |
| 9 | Address fixed | Customer Service fixes order 1051's postal code to 84601 | Address passes. Hold released |
| 10 | Scheduled check | FedEx is back up for order 1054 | Address passes on the next run. Hold released |
| 11 | One box | Shipment 10310, FedEx Ground, one box of 2.4 lb | One label and tracking number |
| 12 | Two boxes | Shipment 10320 has two boxes | Two labels, one tracking number for each box |
| 13 | Carrier refuses a label | The address was changed after its check | No label. FedEx's reason shown |
| 14 | Void | The packer used the wrong box | Labels cancelled at FedEx and marked voided |
| 15 | Void after shipping | Shipment 10310 has already shipped | Void refused |
| 16 | Void on cancel | Shipment 10330 with labels is cancelled | Labels voided at FedEx |
| 17 | No account linked | The order's method is UPS Ground, with no UPS account | No check or label. The reason says no account is linked |
| 18 | Go live | Acme switches its account to live mode | Check passed. Next labels are real |

## Who does what

The plugin checks the address and returns the answer.
The application that owns the order records the answer, holds the order and releases it.
OFBiz order holds and the warehouse application's holds work differently, so the hold stays with the application.

## Out of scope for now

- Live shipping rates at checkout, and choosing the cheapest service.
- Tracking updates after the label is made.
- Third-party billing.
- Customs papers for international shipments.
- Carriers other than FedEx.

## Open questions

- Can one business have two accounts with the same carrier, such as one per store?
- When Customer Service fixes an address, should a failed check put the hold back, or should the person's fix be trusted?
