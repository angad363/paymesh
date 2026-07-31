# ADR-008: Cross-module reads go through a consumer-owned port

## Status

Accepted

## Context

`POST /api/v1/orders` accepts an optional `customerId` and must reject one that
does not exist or belongs to another merchant. That is Order reading Customer —
the first cross-module dependency in the codebase, and therefore the first time
the modular-monolith boundary from ADR-001 has to mean something in code rather
than in a package name.

The obvious thing is for `CreateOrderService` to take `GetCustomerService` as a
constructor argument. It compiles, it works, and it is how a modular monolith
usually stops being modular: after five capabilities, "Customer" is whatever the
union of its callers happens to use, and extracting it means finding every call
site and discovering that each one leaned on a slightly different part of it.

This project also has a standing rule against interfaces with one
implementation, and `CustomerLookup` is exactly that. The rule is right often
enough that the exception needs writing down, or the next reader deletes it.

## Decision

**Order defines the port it needs, in its own package.**

```java
// com.paymesh.order.application
public interface CustomerLookup {
    boolean exists(MerchantId merchantId, String customerId);
}
```

The implementation, `CustomerModuleLookup`, lives in
`com.paymesh.order.infrastructure.customer` and delegates to Customer's
`GetCustomerService`. It and `OrderConfiguration` — which must name the type to
wire it — are the **only two files in `com.paymesh.order` that import
`com.paymesh.customer`**, and a test (`ModuleBoundaryTest`) fails the build if a
third appears. Nothing in `order.api`, `order.application` or `order.domain` can
see Customer at all.

**The consumer owns the contract, not the provider.** The interface states what
Order needs — one boolean, tenant-scoped — rather than what Customer happens to
offer. So the coupling is one three-line question instead of the whole of
Customer's application layer.

**Why the one-implementation rule does not apply.** The usual argument against
such an interface is that it adds indirection for a substitution nobody will
make. Here the substitution is on the roadmap: the SDD's end state is ~15
services, Customer is one of them, and the port is the seam that turns "extract
Customer" from a refactor across every call site into a rewrite of one adapter
class. The interface is not speculative generality; it is the boundary the
modular monolith exists to prove.

**The check is advisory, and the schema is the guarantee.** A customer can be
deleted between the check and the insert, so `orders` carries a **composite**
foreign key on `(merchant_id, customer_id)` — which required a matching unique
constraint on `customers (merchant_id, customer_id)`, added in `V5`. A
single-column FK to `customer_id` would have let an order name *any* customer on
the platform, with only application code standing between a tenant and someone
else's buyer. `CustomerLookup` exists to turn that constraint violation into a
readable `422 CUSTOMER_NOT_FOUND` instead of a 500.

**The rejection does not leak.** `GetCustomerService` already reports another
merchant's customer as not found, so both "no such customer" and "not yours"
return `false` here, and both produce an identical error body. Otherwise the
order endpoint would become an oracle for enumerating other tenants' customer
ids.

## Consequences

Order depends on an interface it owns, so Customer can change its service
signatures without touching Order, and can be extracted into a service by
rewriting one class. The tenant rule is enforced twice, deliberately: once in
the application for a good error message, once in the schema so it holds even
against a write that bypasses the application.

Costs and open edges:

- One more indirection to follow when reading `CreateOrderService`. This is the
  actual price, and it is one file.
- The port is synchronous and in-process. When Customer becomes a service the
  adapter becomes a network call, which brings latency, retries and a failure
  mode that does not exist today — the interface accommodates that, it does not
  solve it.
- `ModuleBoundaryTest` inspects source text rather than bytecode. ArchUnit is the
  right tool and would be worth a dependency once there is a second architecture
  rule to enforce; for one rule, fifteen lines beats a new dependency.
- The unique constraint on `customers (merchant_id, customer_id)` is redundant
  with that table's primary key. That redundancy is the price of a composite
  foreign key, and it is one index.
