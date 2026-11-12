# Sample packets

Packets a caller sends to the shared services, and the results it gets back. They use unigate's field names, the same for every carrier. Made-up data only.
Tests send these packets, with the FedEx client replaced by the replies in `../fedex/`.

| File | Use |
| --- | --- |
| `label-request-one-box.json` | Label request, one parcel, with dimensions |
| `label-request-two-boxes.json` | Label request, two parcels |
| `label-result-one-box.json` | The label result for one parcel |
| `void-request.json` | Void request |
| `address-check-request.json` | Address check request |
| `address-check-result-good.json` | Address check result for a good address |

How tests use these files: `docs/testing.md`.
