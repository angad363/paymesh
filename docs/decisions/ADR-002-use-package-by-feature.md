# ADR-002: Organize backend code by business capability

## Status

Accepted

## Context

PayMesh will contain multiple capabilities, including merchants, customers,
orders, payments, ledger accounting, refunds, settlements, risk, and webhooks.

A global technical-layer structure would distribute each capability across
controller, service, repository, entity, and DTO packages. This makes business
boundaries unclear and increases coupling as the application grows.

## Decision

PayMesh will use package-by-feature.

Each business capability will own its API, application logic, domain model,
and infrastructure implementation.

Example:

````
com.paymesh.merchant
├── api
├── application
├── domain
└── infrastructure
````

Packages will be introduced incrementally. Empty module structures will not
be created before a capability is designed.
