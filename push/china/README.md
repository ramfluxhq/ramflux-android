# push/china

Reserved for a later mainland-China edition. There is no module and no code here.

The seams are in place: `PushProvider` implementations are per delivery channel (`DeliveryChannel.VENDOR` is
reserved), `BrandCompat` sets live in an edition's own compat module, and `ramflux.edition=china` is the property
value that would select the modules. Building with that value fails today with "edition not implemented".
