# FedEx sample replies

One file per scenario. They follow the shapes in FedEx's published REST API specs, with made-up data.
`SHIP.RECIPIENT.POSTALCITY.MISMATCH` comes from FedEx's spec examples. The other error codes are placeholders, to be replaced by real ones from FedEx's test system in issue #13.
Never add a real account number, key, token or customer address here.

| File | Scenario |
| --- | --- |
| `token-ok.json`, `token-wrong-secret.json` | Login |
| `account-check-ok.json`, `account-check-wrong-account.json` | Account check |
| `address-good.json`, `address-undeliverable.json`, `address-city-spelled-differently.json` | Address check |
| `ship-one-box.json`, `ship-two-boxes.json` | Labels |
| `ship-with-note-alert.json` | Label with a NOTE alert, which is not a failure |
| `ship-invalid-postal-code.json` | FedEx refuses the address |
| `cancel-ok.json`, `cancel-not-cancelled.json` | Void, and a void FedEx refuses with a 200 reply |
