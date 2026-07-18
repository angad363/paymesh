# ADR-001: Start PayMesh as a modular monolith

## Status

Accepted

## Context

PayMesh will eventually contain several business capabilities, including
merchants, customers, orders, payments, ledger accounting, refunds, settlements,
risk evaluation, webhooks, and reporting.

Starting with independently deployed microservices would require solving
deployment, networking, distributed transactions, service discovery,
observability, and messaging before the core payment domain is understood.

## Decision

PayMesh will initially be implemented as one Spring Boot application.

The application will be divided into business modules with explicit boundaries.

Possible modules include:

- merchant
- customer
- order
- payment
- ledger
- refund
- settlement
- webhook
- risk

## Consequences

### Positive

- The application is easier to run and debug.
- Database transactions are simpler during early development.
- Business boundaries can be discovered before services are extracted.
- Development requires less infrastructure.

### Negative

- Modules cannot initially be deployed independently.
- Strong discipline is required to prevent modules from becoming tightly coupled.

## Future direction

A module may be extracted into a microservice when it has:

- a clear business responsibility
- stable interfaces
- independent scaling requirements
- independent reliability requirements
- sufficient tests