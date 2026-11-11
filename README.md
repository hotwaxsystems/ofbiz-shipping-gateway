# OFBiz Shipping Gateway

A plugin for Apache OFBiz that connects OFBiz to shipping carriers.
It gets labels, voids labels, and checks carrier accounts.
FedEx is the first carrier. Others follow the same pattern.

## How it fits OFBiz

- It plugs into the carrier setup that OFBiz trunk already has.
  A carrier account is a shipment gateway record (`ShipmentGatewayConfig`).
  A store shipping method (`ProductStoreShipmentMeth`) links a store to that account.
- Every carrier follows one shared set of services.
  OFBiz calls the shared service, and the plugin calls the right carrier.
- Tracking numbers and labels are saved in OFBiz's own shipment route records.
- It does not change OFBiz core code.

## Install

Clone this repository into the `plugins` folder of an OFBiz trunk checkout:

```
cd ofbiz-framework/plugins
git clone https://github.com/hotwaxsystems/ofbiz-shipping-gateway.git shippinggateway
```

## Contributing

Every change goes through a pull request.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
