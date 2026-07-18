# Merchant Domain Discovery

## Purpose

This document defines the initial Merchant domain for PayMesh.

The goal is to understand the business capability before creating controllers,
services, database tables, JPA entities, or repository implementations.

This is the first version of the model. It should remain small enough to
implement and test while still protecting the important business rules.

---

## 1. What is a merchant?

A merchant is a business account that uses PayMesh to create orders, accept
simulated payments, issue refunds, receive settlements, and consume operational
events.

Examples of merchants include:

- an online store
- a restaurant
- a subscription business
- a software platform
- a marketplace seller
- a service provider

A merchant is not the person purchasing goods or services.

The person making a payment is represented by the Customer capability.

---

## 2. Merchant responsibility

The Merchant module owns the identity and lifecycle of businesses using
PayMesh.

Its responsibilities include:

- registering a merchant
- assigning a PayMesh merchant identifier
- storing the merchant's business profile
- storing the merchant's operating country
- storing the merchant's default currency
- tracking the merchant's lifecycle status
- preventing duplicate merchant registration by email
- retrieving merchant information
- controlling valid merchant state transitions

The Merchant module does not own:

- customers
- orders
- payment intents
- payment attempts
- refunds
- ledger entries
- balances
- settlements
- webhook deliveries
- authentication credentials
- user accounts

Those responsibilities belong to separate PayMesh capabilities.

---

## 3. Ubiquitous language

The following terms should be used consistently in code, documentation, API
contracts, and discussions.

### Merchant

A business registered to use PayMesh.

### Merchant identifier

An opaque server-generated identifier for a merchant.

Example:

```text
mrc_01J...
```

### Business name

The public or operational name under which the merchant conducts business.

Example:

```text
FreshBrew Cafe
```

### Merchant email

The primary email address associated with the merchant during initial
registration.

This is not yet an authentication username.

Authentication and merchant users will be designed separately.

### Operating country

The country in which the merchant account primarily operates.

It is represented by a two-letter uppercase country code.

Example:

```text
IN
```

### Default currency

The currency PayMesh uses by default when a merchant creates resources without
explicitly supplying another supported currency.

Example:

```text
INR
```

### Merchant status

The current lifecycle state of the merchant account.

Initial states:

```text
PENDING_VERIFICATION
ACTIVE
SUSPENDED
CLOSED
```

---

## 4. Merchant aggregate

The Merchant is the aggregate root of the Merchant module.

This means that changes to merchant state must occur through the Merchant
aggregate rather than by allowing unrelated code to modify its fields directly.

Conceptual example:

```
merchant.activate();
merchant.suspend(reason);
merchant.close();
```

Avoid unrestricted mutation:

```
merchant.setStatus(MerchantStatus.ACTIVE);
```

A business operation can verify whether a state transition is valid.

A generic setter cannot express or protect that rule.

---

## 5. Initial merchant attributes

The first version of the Merchant model contains:

| Field | Description |
|---|---|
| `merchantId` | Server-generated opaque merchant identifier |
| `businessName` | Merchant's business name |
| `email` | Primary registration email |
| `country` | Two-letter operating-country code |
| `defaultCurrency` | Three-letter default-currency code |
| `status` | Current merchant lifecycle status |
| `createdAt` | UTC timestamp at which the merchant was registered |
| `updatedAt` | UTC timestamp of the most recent merchant change |

The initial model deliberately excludes:

- legal entity type
- tax registration number
- physical address
- website
- telephone number
- business category
- bank account
- settlement schedule
- identity-verification documents
- merchant users
- API keys
- risk level
- pricing plan

Those concepts may be introduced later when a real use case requires them.

---

## 6. Business-name rules

The business name:

- is required
- must not be blank
- is trimmed before use
- must not exceed 200 characters
- preserves meaningful capitalization
- does not need to be globally unique

Examples of valid names:

```text
FreshBrew Cafe
Northwind Traders
Acme Software Private Limited
```

Examples of invalid names:

```text
""
"   "
```

Two different merchants may have the same business name.

Business-name uniqueness would incorrectly prevent legitimate businesses from
sharing similar or identical names.

---

## 7. Email rules

The merchant email:

- is required
- must have a valid email structure
- is trimmed before use
- is normalized before uniqueness comparison
- must be unique among registered merchants
- is stored using the project's chosen normalized representation

For the first version, normalization means:

```text
trim surrounding whitespace
convert to lowercase
```

Example:

```text
Owner@FreshBrew.Example
```

is normalized to:

```text
owner@freshbrew.example
```

The original capitalization does not carry business meaning in PayMesh.

The uniqueness rule is based on the normalized value.

Therefore, the following values represent the same merchant email:

```text
owner@freshbrew.example
Owner@FreshBrew.Example
 owner@freshbrew.example
```

Duplicate email registration produces:

```text
MERCHANT_EMAIL_ALREADY_EXISTS
```

This rule prevents accidental duplicate merchant accounts during the initial
version.

A future merchant-user model may allow several users and email addresses to be
associated with one merchant.

---

## 8. Country rules

The operating country:

- is required
- uses a two-letter uppercase code
- is validated against supported PayMesh countries
- is not free-form text

Valid format examples:

```text
IN
US
GB
```

Invalid examples:

```text
India
in
IND
```

Format validation and supported-country validation are different.

For example:

```text
ZZ
```

matches the two-letter format but may not be a supported country.

The API boundary validates the structure.

The domain or application layer validates whether PayMesh supports the country.

The initial list of supported countries will be decided during implementation
configuration.

---

## 9. Currency rules

The default currency:

- is required
- uses a three-letter uppercase currency code
- must be supported by PayMesh
- must be compatible with the merchant's operating country according to
  PayMesh policy

Valid format examples:

```text
INR
USD
EUR
```

Invalid format examples:

```text
inr
₹
RUPEES
```

A syntactically valid currency may still be unsupported.

The first implementation may begin with a deliberately small supported set.

Example:

```text
IN → INR
US → USD
```

The complete country and currency policy should not be hard-coded throughout
the application.

It will eventually belong to a dedicated policy or configuration component.

---

## 10. Initial merchant status

A newly registered merchant starts in:

```text
PENDING_VERIFICATION
```

The merchant does not start as `ACTIVE`.

This reflects an important payment-platform rule:

> Registration and permission to process payments are not the same event.

Even in a simulated system, separating registration from activation creates a
realistic onboarding lifecycle.

The first registration endpoint creates the merchant account but does not
complete verification.

---

## 11. Merchant lifecycle

The initial lifecycle is:

```text
PENDING_VERIFICATION
        |
        v
      ACTIVE
        |
        v
    SUSPENDED
        |
        v
      ACTIVE
```

A merchant may also move to:

```text
CLOSED
```

`CLOSED` is a terminal state.

Conceptual transitions:

```text
PENDING_VERIFICATION → ACTIVE
PENDING_VERIFICATION → CLOSED

ACTIVE → SUSPENDED
ACTIVE → CLOSED

SUSPENDED → ACTIVE
SUSPENDED → CLOSED

CLOSED → no further state
```

---

## 12. Status meanings

### PENDING_VERIFICATION

The merchant has registered but has not completed PayMesh onboarding checks.

A pending merchant:

- exists in PayMesh
- can be retrieved
- cannot process payments
- cannot receive settlements
- may continue onboarding later

### ACTIVE

The merchant has completed the required simulated checks and may use supported
PayMesh payment capabilities.

An active merchant may eventually:

- create customers
- create orders
- create payment intents
- receive payments
- issue refunds
- receive settlements

### SUSPENDED

The merchant is temporarily prevented from using restricted PayMesh
capabilities.

Possible future reasons include:

- risk review
- policy violation
- unusual activity
- manual operational action

Suspension preserves merchant history and does not delete the merchant.

### CLOSED

The merchant relationship has ended.

A closed merchant:

- remains available for historical and audit purposes
- cannot create new payment operations
- cannot return to an active state in the initial design

`CLOSED` is terminal.

---

## 13. Merchant invariants

The Merchant aggregate must always satisfy the following rules:

1. A merchant has exactly one merchant identifier.
2. The identifier is generated by PayMesh.
3. The identifier does not change.
4. The business name is not blank.
5. The normalized email is not blank.
6. The normalized email is unique across merchants.
7. The country uses the required representation.
8. The default currency uses the required representation.
9. The country and currency combination is supported.
10. A newly registered merchant starts as `PENDING_VERIFICATION`.
11. A merchant status changes only through an allowed business transition.
12. A closed merchant cannot be reactivated.
13. `createdAt` is generated by PayMesh and does not change.
14. `updatedAt` never occurs before `createdAt`.
15. Clients cannot directly choose the initial merchant status.

Some invariants belong inside the Merchant object.

Other invariants require access to external information.

Example:

```text
Business name is not blank
```

can be protected inside the domain model.

Example:

```text
Email is globally unique
```

requires checking existing merchants through a repository.

The application service will coordinate rules requiring external dependencies.

---

## 14. Initial use cases

The first Merchant module supports two public use cases.

### Register merchant

Creates a new merchant in `PENDING_VERIFICATION`.

Inputs:

```text
business name
email
country
default currency
```

Result:

```text
new merchant representation
```

Possible failures:

```text
VALIDATION_FAILED
MERCHANT_EMAIL_ALREADY_EXISTS
UNSUPPORTED_COUNTRY
UNSUPPORTED_CURRENCY
UNSUPPORTED_COUNTRY_CURRENCY
```

### Get merchant

Retrieves a merchant by its public merchant identifier.

Input:

```text
merchant identifier
```

Result:

```text
merchant representation
```

Possible failure:

```text
MERCHANT_NOT_FOUND
```

---

## 15. Use cases not included initially

The following operations are deliberately postponed:

```text
List merchants
Update merchant profile
Change merchant email
Change default currency
Activate merchant
Suspend merchant
Reactivate merchant
Close merchant
Delete merchant
Upload verification documents
Create API credentials
Manage merchant users
Configure settlement accounts
Configure webhook endpoints
```

The lifecycle is defined now because it affects the domain model.

Public commands for every lifecycle transition will be designed only when their
actors and authorization rules are clear.

For example, merchant activation may be an internal operations command rather
than a merchant-controlled public API.

---

## 16. Registration idempotency decision

The first Merchant registration endpoint will not require idempotency-key
infrastructure.

Duplicate registration is initially controlled through normalized email
uniqueness.

A repeated request after a successful registration may therefore return:

```text
409 MERCHANT_EMAIL_ALREADY_EXISTS
```

rather than replaying the original creation response.

This is acceptable for the first Merchant capability because registration does
not create an immediate financial effect.

Idempotency will become mandatory for operations such as:

- creating payment intents
- confirming payments
- issuing refunds
- creating settlements

The preliminary Merchant API example that included `Idempotency-Key` should be
updated to reflect this decision.

---

## 17. Deletion decision

Merchants are not physically deleted through the initial public API.

Deleting a merchant would risk losing:

- payment ownership history
- refund relationships
- settlement history
- ledger references
- webhook history
- audit evidence

The lifecycle uses `CLOSED` instead of deletion.

Data-retention and privacy requirements will be addressed separately.

---

## 18. Security boundary

The initial domain design does not yet define authentication or authorization.

Eventually:

- merchant users will authenticate
- requests will be associated with a merchant
- operators may manage merchant lifecycle states
- merchants must not access other merchants' data

The Merchant domain must not assume that possession of a merchant identifier
grants access.

Authorization belongs at the application and security boundary.

---

## 19. Events

The Merchant module may eventually publish domain or integration events.

Potential events include:

```text
merchant.registered
merchant.activated
merchant.suspended
merchant.reactivated
merchant.closed
```

No event implementation is added during this design task.

Events will be introduced when there is a real consumer or integration use case.

---

## 20. Open questions

The following questions are intentionally postponed:

- Which countries are supported first?
- Which currencies are supported first?
- Who is allowed to activate a merchant?
- Is verification automatic or manually simulated?
- Should suspended merchants be allowed to issue refunds?
- Can a merchant change its operating country?
- Can a merchant change its default currency?
- How are merchant users represented?
- How are business verification documents represented?
- What data-retention rules apply to closed merchants?

These questions should be answered when the relevant use cases are introduced.

They do not block the first registration and retrieval implementation.

---

## 21. Initial module boundary

The future Java package will begin as:

```text
com.paymesh.merchant
```

Its internal structure will be introduced only when implementation begins.

Likely future structure:

```text
merchant/
├── api/
├── application/
├── domain/
└── infrastructure/
```

Responsibilities:

```text
api
→ HTTP request and response handling

application
→ use-case coordination

domain
→ merchant behavior and business invariants

infrastructure
→ persistence and external integrations
```

No empty package tree should be created during domain discovery.

---

## 22. Domain discovery result

The first Merchant implementation should support:

```text
Register a merchant
Retrieve a merchant by identifier
```

The initial merchant contains:

```text
merchantId
businessName
email
country
defaultCurrency
status
createdAt
updatedAt
```

A newly registered merchant starts as:

```text
PENDING_VERIFICATION
```

The first implementation should remain small while preserving the boundaries
required for future onboarding, payments, settlements, and risk workflows.
