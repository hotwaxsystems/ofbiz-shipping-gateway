# Roadmap

The carriers this plugin will support, in the order we plan to build them.
The first two prove the shared design and the coding agent skill.
After that, carriers come in the order our clients need them.

## 1. FedEx

Status: planned. Issues are under the FedEx milestone.

Built by hand. It sets the pattern every other carrier follows: account check, address check, labels and void.

## 2. UPS

Status: planned, after FedEx and the "add a shipping carrier" skill.

Built by a coding agent using only the skill. It tests that the skill is enough.

## 3. Next carriers

In no fixed order yet. HotWax has built each of these before, for Apache OFBiz or other platforms, so the carrier's quirks are known.

### USA

| Carrier | What it is |
| --- | --- |
| Veho | Regional carrier for fast home delivery |
| Jitsu | Regional carrier for same-day and next-day delivery |

### Canada

| Carrier | What it is |
| --- | --- |
| Canada Post | The national postal carrier |
| Purolator | Large Canadian courier |

### Shipping platforms

A shipping platform reaches many carriers through one connection.

| Platform | What it is |
| --- | --- |
| ShipHawk | Shipping platform with rates and labels for many carriers |
| ShipStation | Shipping platform with rates and labels for many carriers |

## How a carrier moves up

A carrier moves into the next slot when a client needs it.
Each carrier is built with the same carrier template tasks, and gets its own milestone and label.
