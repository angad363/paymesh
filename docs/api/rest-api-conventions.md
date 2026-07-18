# PayMesh REST API Conventions

## Purpose

This document defines the baseline HTTP and JSON conventions for PayMesh APIs.

The goal is to provide predictable behavior across all PayMesh capabilities, including:

- merchants
- customers
- orders
- payment intents
- payments
- refunds
- ledger entries
- balances
- settlements
- webhooks
- notifications
- risk operations
- reporting

These conventions establish a consistent external contract for API clients.

They also help PayMesh remain:

- easy to understand
- easy to document
- easy to test
- safe to evolve
- consistent across modules
- suitable for future service extraction

These conventions are defaults rather than substitutes for engineering judgment.

Any intentional exception should have a clear reason and should be documented when it changes a public contract.

---

## 1. Base path and API versioning

Public PayMesh APIs use the following base path:

```text
/api/v1
```

Examples:

```text
/api/v1/merchants
/api/v1/customers
/api/v1/orders
/api/v1/payment-intents
/api/v1/payments
/api/v1/refunds
```

The API version is included in the URL because it is:

- explicit
- easy for clients to understand
- easy to document
- easy to route through an API gateway
- visible in logs and traces
- straightforward to test

Example controller mapping:

```java
@RestController
@RequestMapping("/api/v1/merchants")
class MerchantController {
}
```

Versioning does not mean every internal implementation change requires a new API version.

A new API version is required only when introducing an externally breaking contract change that cannot be handled compatibly.

Examples of changes that may require a new API version:

- removing a response field
- renaming a request or response field
- changing a field type
- changing the meaning of an existing status
- making an optional request field mandatory
- changing endpoint behavior incompatibly
- changing the structure of an existing response

Internal refactoring does not require a new version when the external contract remains unchanged.

Avoid placing implementation versions in the path:

```text
/api/spring-v1/merchants
/api/database-v2/payments
```

API versions describe client-facing contracts, not internal technology.

---

## 2. Resource naming

URLs should represent resources rather than implementation actions.

Use plural nouns for resource collections.

Preferred:

```text
/merchants
/customers
/orders
/payment-intents
/payments
/refunds
/settlements
/webhook-endpoints
```

Avoid verbs in normal resource URLs:

```text
/createMerchant
/getCustomer
/updateOrder
/processPayment
/deleteRefund
```

HTTP methods already describe the operation being requested.

Preferred:

```http
POST /api/v1/merchants
GET /api/v1/merchants/{merchantId}
PATCH /api/v1/merchants/{merchantId}
```

Avoid:

```http
POST /api/v1/createMerchant
GET /api/v1/getMerchant/{merchantId}
POST /api/v1/updateMerchant
```

Use lowercase words separated by hyphens for multi-word URL segments.

Preferred:

```text
/payment-intents
/webhook-endpoints
/ledger-entries
/available-balances
```

Avoid:

```text
/paymentIntents
/payment_intents
/PaymentIntents
```

URL naming and JSON naming are separate concerns.

URLs use lowercase kebab case.

JSON fields use lower camel case.

---

## 3. Resource identifiers

Resource identifiers appear in path parameters.

Examples:

```http
GET /api/v1/merchants/{merchantId}
GET /api/v1/customers/{customerId}
GET /api/v1/orders/{orderId}
GET /api/v1/payment-intents/{paymentIntentId}
GET /api/v1/refunds/{refundId}
```

Use descriptive parameter names rather than a generic `{id}`.

Preferred:

```java
@GetMapping("/{merchantId}")
MerchantResponse getMerchant(
        @PathVariable String merchantId
) {
    // ...
}
```

Avoid:

```java
@GetMapping("/{id}")
MerchantResponse getMerchant(
        @PathVariable String id
) {
    // ...
}
```

Descriptive names make:

- logs easier to understand
- controller signatures clearer
- generated API documentation more readable
- error messages less ambiguous

Clients must treat identifiers as opaque values.

They must not infer information from the identifier structure.

---

## 4. Nested resources

Use nested paths only when the child resource is meaningfully scoped by its parent.

Example:

```http
GET /api/v1/merchants/{merchantId}/customers
```

This can be appropriate when retrieving customers belonging to a specific merchant.

Avoid excessive nesting:

```text
/api/v1/merchants/{merchantId}/customers/{customerId}/orders/{orderId}/payments/{paymentId}/refunds
```

Deeply nested paths:

- become difficult to read
- create long controller mappings
- duplicate identifiers unnecessarily
- make resources appear less independently addressable

When a resource has its own stable identifier, prefer a direct endpoint:

```http
GET /api/v1/refunds/{refundId}
```

A nested collection may still be useful for expressing ownership:

```http
GET /api/v1/payments/{paymentId}/refunds
```

Use nesting to represent meaningful relationships, not the complete database hierarchy.

---

## 5. HTTP methods

Use HTTP methods according to their intended semantics.

| Method | Purpose |
|---|---|
| `GET` | Retrieve a resource or collection |
| `POST` | Create a resource or trigger a business command |
| `PUT` | Replace a complete resource when replacement semantics are appropriate |
| `PATCH` | Partially update an existing resource |
| `DELETE` | Remove or deactivate a resource when deletion is supported |

Examples:

```http
POST   /api/v1/merchants
GET    /api/v1/merchants/{merchantId}
PATCH  /api/v1/merchants/{merchantId}
DELETE /api/v1/webhook-endpoints/{webhookEndpointId}
```

### GET

`GET` must not intentionally modify business state.

Preferred:

```http
GET /api/v1/payment-intents/{paymentIntentId}
```

Avoid using `GET` for actions:

```http
GET /api/v1/payment-intents/{paymentIntentId}/confirm
```

A `GET` request may be:

- cached
- retried automatically
- prefetched
- replayed by monitoring tools

Using it for state changes can create dangerous unintended effects.

### POST

Use `POST` for:

- creating resources
- commands with side effects
- domain actions that do not fit normal CRUD semantics

Examples:

```http
POST /api/v1/merchants
POST /api/v1/payment-intents
POST /api/v1/payment-intents/{paymentIntentId}/confirm
POST /api/v1/payments/{paymentId}/refunds
```

### PUT

Use `PUT` only when the client replaces the complete resource representation or when full replacement semantics are explicitly intended.

Do not use `PUT` merely because an endpoint updates data.

### PATCH

Use `PATCH` for partial updates.

Example:

```http
PATCH /api/v1/merchants/{merchantId}
```

Request:

```json
{
  "businessName": "FreshBrew Coffee Private Limited"
}
```

Patch semantics must be documented clearly, especially how omitted and `null` fields are interpreted.

### DELETE

Use `DELETE` only when resource deletion or deactivation is genuinely supported.

Financial records such as payments, ledger entries, and settlements will normally not be physically deleted through public APIs.

Their history may be legally, operationally, or financially significant.

---

## 6. Domain action endpoints

Some payment operations are commands rather than normal CRUD updates.

Examples:

```http
POST /api/v1/payment-intents/{paymentIntentId}/confirm
POST /api/v1/payment-intents/{paymentIntentId}/cancel
POST /api/v1/refunds/{refundId}/cancel
POST /api/v1/merchants/{merchantId}/activate
```

Action names should:

- represent real business commands
- use clear verbs
- avoid generic terms such as `process`
- not replace ordinary resource design

Preferred:

```http
POST /api/v1/payment-intents/{paymentIntentId}/confirm
```

Avoid:

```http
POST /api/v1/payment-intents/{paymentIntentId}/process
```

`Confirm` describes the business intent.

`Process` describes an implementation activity and is ambiguous.

Action endpoints should normally use `POST` because they change state and may not be naturally idempotent without an idempotency mechanism.

---

## 7. HTTP success status codes

Use success status codes consistently.

| Scenario | Status |
|---|---|
| Resource retrieved | `200 OK` |
| Resource created | `201 Created` |
| Successful operation with no response body | `204 No Content` |
| Asynchronous operation accepted | `202 Accepted` |

### 200 OK

Use `200 OK` when returning an existing resource or a successful operation result.

Example:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "status": "ACTIVE"
}
```

### 201 Created

Use `201 Created` when a new resource is created.

The response should normally include a `Location` header:

```http
HTTP/1.1 201 Created
Location: /api/v1/merchants/mrc_01J...
Content-Type: application/json
```

The response may also include the created representation:

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

Do not return `200 OK` for every successful creation.

### 202 Accepted

Use `202 Accepted` when a request has been accepted but processing will continue asynchronously.

Example:

```http
POST /api/v1/settlements
```

Response:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/settlements/stl_01J...
```

The response should provide a way to inspect the asynchronous operation or resource later.

Do not use `202 Accepted` when the operation has already completed synchronously.

### 204 No Content

Use `204 No Content` when the operation succeeds and no response body is needed.

Example:

```http
DELETE /api/v1/webhook-endpoints/whe_01J...
```

Response:

```http
HTTP/1.1 204 No Content
```

A `204` response must not contain a JSON body.

---

## 8. HTTP client-error status codes

Use client-error status codes consistently.

| Scenario | Status |
|---|---|
| Malformed JSON or invalid request syntax | `400 Bad Request` |
| Missing or invalid authentication | `401 Unauthorized` |
| Authenticated but not permitted | `403 Forbidden` |
| Resource does not exist | `404 Not Found` |
| Method not supported for the endpoint | `405 Method Not Allowed` |
| Request conflicts with current state | `409 Conflict` |
| Unsupported request media type | `415 Unsupported Media Type` |
| Semantically invalid request | `422 Unprocessable Content` |
| Rate limit exceeded | `429 Too Many Requests` |

### 400 Bad Request

Use `400 Bad Request` when the server cannot parse or understand the request structure.

Examples:

- malformed JSON
- invalid JSON syntax
- invalid path parameter format
- invalid query parameter syntax
- missing required request body
- unreadable request payload

Example:

```json
{
  "businessName": "FreshBrew Cafe",
  "email": "owner@example.com",
}
```

The trailing comma makes the JSON malformed.

This is a `400 Bad Request`.

### 401 Unauthorized

Use `401 Unauthorized` when authentication is missing or invalid.

Examples:

- no authentication credentials
- expired access token
- invalid access token
- malformed authentication header

Despite the name, `401` means the request is unauthenticated.

### 403 Forbidden

Use `403 Forbidden` when the caller is authenticated but lacks permission.

Examples:

- a merchant attempts to access another merchant's resource
- an operator lacks the required role
- an API key does not have the required capability

### 404 Not Found

Use `404 Not Found` when a requested resource does not exist or is not visible to the current caller.

Examples:

```text
Merchant does not exist
Payment intent does not exist
Refund is not accessible to the authenticated merchant
```

In multi-tenant systems, returning `404` instead of `403` may sometimes prevent resource-existence disclosure.

That choice should be applied consistently.

### 405 Method Not Allowed

Use `405 Method Not Allowed` when the route exists but does not support the requested HTTP method.

Example:

```http
DELETE /api/v1/payments/{paymentId}
```

When payment deletion is unsupported, the framework may return `405`.

### 409 Conflict

Use `409 Conflict` when the request conflicts with existing state or uniqueness constraints.

Examples:

```text
Merchant email already registered
Payment intent already confirmed
Order cannot be cancelled after payment
Refund already completed
Same idempotency key reused with a different request
Resource version conflict
```

A `409` response indicates that the request may be structurally valid but cannot be completed because of current resource state.

### 415 Unsupported Media Type

Use `415 Unsupported Media Type` when the request body uses an unsupported content type.

Example:

```http
Content-Type: text/plain
```

when the endpoint accepts only:

```http
Content-Type: application/json
```

### 422 Unprocessable Content

Use `422 Unprocessable Content` when the request is syntactically valid but fails semantic or field validation.

Examples:

```text
Business name is blank
Email format is invalid
Amount is zero or negative
Currency code is unsupported
Country code has an invalid format
```

PayMesh uses the following distinction:

- `400` for malformed or unreadable requests
- `422` for structurally readable requests with invalid fields
- `409` for conflicts with existing business state

This distinction must remain consistent across modules.

### 429 Too Many Requests

Use `429 Too Many Requests` when the caller exceeds a defined rate limit.

A response may include:

```http
Retry-After: 60
```

Rate-limit behavior will be introduced when API security and traffic controls are implemented.

---

## 9. HTTP server-error status codes

Unexpected failures use server-error status codes.

| Scenario | Status |
|---|---|
| Unexpected internal failure | `500 Internal Server Error` |
| Invalid response from an upstream provider | `502 Bad Gateway` |
| Required dependency temporarily unavailable | `503 Service Unavailable` |
| Upstream dependency timed out | `504 Gateway Timeout` |

### 500 Internal Server Error

Use `500 Internal Server Error` for unexpected failures within PayMesh.

The client must receive a safe generic response.

Do not expose:

- stack traces
- SQL statements
- database errors
- internal class names
- filesystem paths
- credentials
- provider secrets

### 502 Bad Gateway

Use `502 Bad Gateway` when PayMesh receives an invalid or unusable response from an upstream provider.

Example:

```text
The simulated payment provider returns an invalid response payload.
```

### 503 Service Unavailable

Use `503 Service Unavailable` when a required dependency is temporarily unavailable.

Examples:

- payment provider unavailable
- database connection pool exhausted
- Kafka unavailable for a required synchronous workflow
- maintenance mode

### 504 Gateway Timeout

Use `504 Gateway Timeout` when an upstream dependency does not respond within the allowed time.

Errors from dependencies must be translated into PayMesh's standard error format rather than returned directly.

---

## 10. JSON naming

JSON field names use lower camel case.

Preferred:

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "defaultCurrency": "INR",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

Avoid mixing naming styles:

```json
{
  "merchant_id": "mrc_01J...",
  "BusinessName": "FreshBrew Cafe",
  "default-currency": "INR",
  "created_at": "2026-07-18T10:15:30Z"
}
```

Database column naming is a separate concern.

Database columns may use snake case:

```text
merchant_id
business_name
created_at
```

The external JSON contract must not be coupled to database naming.

JSON field names should be:

- descriptive
- stable
- unambiguous
- consistent across endpoints

Avoid vague names such as:

```json
{
  "id": "...",
  "data": "...",
  "value": "...",
  "info": "..."
}
```

Prefer:

```json
{
  "paymentIntentId": "...",
  "providerReference": "...",
  "capturedAmount": 50000
}
```

---

## 11. JSON object structure

Responses should have explicit, predictable structures.

A single resource may be returned directly:

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "status": "ACTIVE"
}
```

A collection response should normally use a wrapper:

```json
{
  "data": [
    {
      "merchantId": "mrc_01J...",
      "businessName": "FreshBrew Cafe",
      "status": "ACTIVE"
    }
  ],
  "pagination": {
    "limit": 20,
    "nextCursor": null,
    "hasMore": false
  }
}
```

The collection wrapper provides space for:

- pagination metadata
- links
- aggregate information
- future compatible metadata

Do not return an unbounded raw array for resources that may require pagination:

```json
[
  {
    "merchantId": "mrc_01J..."
  }
]
```

Response structures must not change unpredictably based on data volume.

For example, an endpoint must not return an object for one item and an array for multiple items.

---

## 12. Request models

Every structured write endpoint should use a dedicated request model.

Example:

```java
public record RegisterMerchantRequest(
        String businessName,
        String email,
        String country,
        String defaultCurrency
) {
}
```

Request models:

- represent the external API contract
- define fields accepted from clients
- may contain boundary validation annotations
- must not be persistence entities
- must not be reused as domain objects
- should not contain business operations
- should not expose internal-only fields

Do not accept generic maps for structured API requests:

```java
Map<String, Object>
```

Generic maps:

- remove compile-time safety
- make validation harder
- make documentation less precise
- increase casting errors
- hide the API contract

Avoid exposing server-controlled fields in creation requests.

For example, clients should not be able to choose:

```json
{
  "merchantId": "mrc_custom",
  "status": "ACTIVE",
  "createdAt": "2026-01-01T00:00:00Z"
}
```

Identifiers, creation timestamps, and protected statuses should normally be generated or controlled by the server.

Use separate models for separate operations.

Preferred:

```java
RegisterMerchantRequest
UpdateMerchantRequest
ConfirmPaymentIntentRequest
CreateRefundRequest
```

Avoid one large request type reused for every operation:

```java
MerchantRequest
PaymentRequest
GenericUpdateRequest
```

Separate models keep each contract focused and prevent accidental acceptance of irrelevant fields.

---

## 13. Response models

Every endpoint should return an explicit response model.

Example:

```java
public record MerchantResponse(
        String merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        String status,
        Instant createdAt
) {
}
```

Do not return JPA entities directly from controllers.

Returning persistence entities can cause:

- accidental field exposure
- lazy-loading failures
- circular JSON serialization
- database structure leaking into public contracts
- uncontrolled breaking API changes
- security vulnerabilities
- unintended update behavior

Response models should contain only fields safe and useful for clients.

Internal fields should remain hidden.

Examples of fields that may be internal:

- database version columns
- token hashes
- internal fraud scores
- private provider credentials
- internal processing notes
- encryption metadata
- deleted flags used only internally

Different endpoints may use different response models when their needs differ.

Examples:

```java
MerchantSummaryResponse
MerchantDetailsResponse
PaymentIntentResponse
PaymentAttemptResponse
RefundResponse
```

Do not expose every available internal field merely because it exists.

---

## 14. Request and response separation

A request model and response model should not normally be the same type.

Creation request:

```java
public record RegisterMerchantRequest(
        String businessName,
        String email,
        String country,
        String defaultCurrency
) {
}
```

Creation response:

```java
public record MerchantResponse(
        String merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        String status,
        Instant createdAt
) {
}
```

The response contains server-controlled values that do not belong in the request:

- identifier
- status
- creation timestamp

Separate types make the ownership of each field explicit.

---

## 15. Validation boundaries

Syntactic and structural validation happens at the API boundary.

Examples:

```text
Required field is missing
String is blank
Email format is invalid
String exceeds maximum length
Amount is not positive
Currency code has an invalid format
Country code has an invalid format
```

Example request model:

```java
public record RegisterMerchantRequest(

        @NotBlank(message = "Business name is required.")
        @Size(
                max = 200,
                message = "Business name must not exceed 200 characters."
        )
        String businessName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        String email,

        @NotBlank(message = "Country is required.")
        @Pattern(
                regexp = "^[A-Z]{2}$",
                message = "Country must be a two-letter uppercase code."
        )
        String country,

        @NotBlank(message = "Default currency is required.")
        @Pattern(
                regexp = "^[A-Z]{3}$",
                message = "Currency must be a three-letter uppercase code."
        )
        String defaultCurrency
) {
}
```

Controller:

```java
@PostMapping
ResponseEntity<MerchantResponse> registerMerchant(
        @Valid @RequestBody RegisterMerchantRequest request
) {
    // ...
}
```

Business validation belongs in application or domain logic.

Examples:

```text
Merchant email is already registered
Merchant cannot be activated before verification
Payment cannot be confirmed from its current state
Refund exceeds the refundable amount
Settlement account is inactive
Currency is not supported for the merchant
```

Validation annotations must not replace domain invariants.

A request passing `@Valid` does not mean the business operation is valid.

The API layer validates the shape of the request.

The application and domain layers validate whether the requested operation is allowed.

---

## 16. Unknown JSON fields

The treatment of unknown request fields must be consistent.

During early development, PayMesh should prefer rejecting unknown fields for write requests because this helps detect:

- client spelling mistakes
- outdated request formats
- accidental use of unsupported fields
- incorrect assumptions about the contract

Example request:

```json
{
  "businessName": "FreshBrew Cafe",
  "emali": "owner@example.com"
}
```

The misspelled `emali` field should not be silently ignored while the required `email` field is missing.

The exact Jackson configuration will be established when shared API infrastructure is implemented.

Any future decision to ignore unknown fields for compatibility must be deliberate and documented.

---

## 17. Standard error response

All PayMesh API errors use one consistent response shape.

Example:

```json
{
  "type": "https://api.paymesh.dev/problems/merchant-not-found",
  "title": "Merchant not found",
  "status": 404,
  "code": "MERCHANT_NOT_FOUND",
  "detail": "No merchant exists with the supplied identifier.",
  "instance": "/api/v1/merchants/mrc_01J...",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

Field meanings:

| Field | Meaning |
|---|---|
| `type` | Stable documentation identifier for the problem type |
| `title` | Short human-readable summary |
| `status` | HTTP response status code |
| `code` | Stable machine-readable PayMesh error code |
| `detail` | Contextual explanation safe for the caller |
| `instance` | Request path associated with the failure |
| `requestId` | Correlation identifier for support and diagnostics |
| `timestamp` | UTC time at which the response was produced |

### type

The `type` field identifies the category of problem.

Example:

```text
https://api.paymesh.dev/problems/merchant-not-found
```

The URI does not need to be implemented as a real documentation page immediately, but it should be stable.

It may later point to client-facing error documentation.

### title

The `title` field is a concise summary.

Example:

```text
Merchant not found
```

It should not contain dynamic implementation details.

### status

The `status` field repeats the HTTP status code in the JSON body.

Example:

```json
"status": 404
```

It must match the actual HTTP response status.

### code

The `code` field is the stable machine-readable PayMesh error identifier.

Example:

```text
MERCHANT_NOT_FOUND
```

Client applications should branch on `code`, not on `title` or `detail`.

### detail

The `detail` field gives a safe explanation for this occurrence.

It may contain contextual information but must not expose sensitive internal details.

### instance

The `instance` field identifies the request path associated with the error.

Example:

```text
/api/v1/merchants/mrc_01J...
```

### requestId

The `requestId` field allows clients, developers, and support operators to correlate an API failure with:

- logs
- traces
- provider calls
- downstream events
- operational dashboards

### timestamp

The `timestamp` field records when the error response was produced.

It uses UTC ISO 8601 format.

---

## 18. Validation-error response

Field validation errors extend the standard error shape.

Example:

```json
{
  "type": "https://api.paymesh.dev/problems/validation-failed",
  "title": "Request validation failed",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z",
  "errors": [
    {
      "field": "businessName",
      "code": "NotBlank",
      "message": "Business name is required."
    },
    {
      "field": "email",
      "code": "Email",
      "message": "Email must be valid."
    }
  ]
}
```

Each field error contains:

| Field | Meaning |
|---|---|
| `field` | Name of the invalid request field |
| `code` | Stable or framework-derived validation rule identifier |
| `message` | Human-readable validation message |

Validation errors should be:

- deterministic
- safe for clients
- ordered consistently where practical
- tied to external JSON field names
- free from internal Java class names

Do not expose internal paths such as:

```text
registerMerchantRequest.businessName
```

Prefer:

```text
businessName
```

For nested objects, a clear property path may be used:

```text
billingAddress.postalCode
```

Validation messages should describe how to correct the request.

Preferred:

```text
Amount must be greater than zero.
```

Avoid:

```text
Invalid input.
```

---

## 19. Error codes

PayMesh error codes use uppercase snake case.

Examples:

```text
VALIDATION_FAILED
MALFORMED_REQUEST
MERCHANT_NOT_FOUND
MERCHANT_EMAIL_ALREADY_EXISTS
MERCHANT_NOT_ACTIVE
PAYMENT_INTENT_NOT_FOUND
PAYMENT_ALREADY_CONFIRMED
INVALID_PAYMENT_STATE
REFUND_AMOUNT_EXCEEDED
REFUND_NOT_ALLOWED
IDEMPOTENCY_KEY_CONFLICT
RATE_LIMIT_EXCEEDED
INTERNAL_ERROR
```

Error codes should be:

- specific
- stable
- machine-readable
- independent of human-readable wording
- unique enough to identify the failure category

Avoid generic codes when a specific failure is known:

```text
ERROR
FAILED
BAD_REQUEST
INVALID
UNKNOWN
```

Once published, an error code becomes part of the API contract.

Do not reuse an existing code for a different meaning.

Do not silently rename codes without considering backward compatibility.

Error codes should describe the failure, not the Java exception class.

Preferred:

```text
MERCHANT_EMAIL_ALREADY_EXISTS
```

Avoid:

```text
DUPLICATE_MERCHANT_EMAIL_EXCEPTION
```

---

## 20. Error safety

Error responses must not expose:

- stack traces
- SQL statements
- table names
- database constraint names
- internal hostnames
- filesystem paths
- source-code line numbers
- access tokens
- API secrets
- authentication claims
- provider credentials
- internal class names
- raw upstream error payloads containing sensitive data

Unsafe:

```json
{
  "error": "org.postgresql.util.PSQLException: duplicate key value violates unique constraint merchants_email_key"
}
```

Safe:

```json
{
  "type": "https://api.paymesh.dev/problems/merchant-email-already-exists",
  "title": "Merchant email already registered",
  "status": 409,
  "code": "MERCHANT_EMAIL_ALREADY_EXISTS",
  "detail": "A merchant is already registered with the supplied email address.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

Unexpected failures should return a generic error:

```json
{
  "type": "https://api.paymesh.dev/problems/internal-error",
  "title": "Internal server error",
  "status": 500,
  "code": "INTERNAL_ERROR",
  "detail": "An unexpected error occurred.",
  "instance": "/api/v1/payment-intents",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

Detailed failure information belongs in protected logs and traces.

---

## 21. Dates and timestamps

API timestamps use ISO 8601 format in UTC.

Example:

```text
2026-07-18T10:15:30Z
```

Java representation:

```java
Instant
```

Examples of timestamp fields:

```json
{
  "createdAt": "2026-07-18T10:15:30Z",
  "updatedAt": "2026-07-18T11:45:10Z",
  "confirmedAt": "2026-07-18T12:00:00Z"
}
```

Avoid local timestamp values without an offset:

```text
2026-07-18T10:15:30
```

That value is ambiguous because it does not identify a timezone.

Use `Instant` for events that represent a precise moment.

Examples:

- merchant creation
- payment confirmation
- refund completion
- webhook delivery attempt
- ledger posting
- settlement creation

Use `LocalDate` only for date-only concepts.

Examples:

```text
settlement business date
reporting date
billing date
```

Clients may convert UTC timestamps into local time for display.

The server should not change timestamp formats based on the caller's timezone.

---

## 22. Money representation

Money amounts are represented using integer minor units.

Example:

```json
{
  "amount": 99900,
  "currency": "INR"
}
```

For a currency using two decimal places, this represents:

```text
999.00 INR
```

The API must not use binary floating-point values for money.

Avoid:

```json
{
  "amount": 999.00
}
```

Floating-point arithmetic can introduce rounding errors.

Use integral minor units:

```json
{
  "amount": 99900,
  "currency": "INR"
}
```

Currency uses an uppercase ISO-style currency code.

Examples:

```text
INR
USD
EUR
GBP
```

Amount and currency should normally appear together.

Preferred:

```json
{
  "amount": 50000,
  "currency": "INR"
}
```

Avoid ambiguous amounts:

```json
{
  "amount": 50000
}
```

unless the currency is unambiguously defined by the containing resource and documented.

Field names should make specialized monetary values clear.

Examples:

```json
{
  "authorizedAmount": 50000,
  "capturedAmount": 50000,
  "refundedAmount": 10000,
  "currency": "INR"
}
```

Negative monetary values should not be used unless the contract explicitly represents signed accounting values.

For normal payment and refund commands, amounts should generally be positive.

The exact money value-object implementation will be introduced when the first domain use case requires it.

---

## 23. Public identifiers

Public PayMesh resource identifiers use opaque, prefixed strings.

Examples:

```text
mrc_01J...
cus_01J...
ord_01J...
pi_01J...
pay_01J...
ref_01J...
stl_01J...
whe_01J...
evt_01J...
req_01J...
```

Example prefixes:

| Resource | Prefix |
|---|---|
| Merchant | `mrc_` |
| Customer | `cus_` |
| Order | `ord_` |
| Payment intent | `pi_` |
| Payment | `pay_` |
| Refund | `ref_` |
| Settlement | `stl_` |
| Webhook endpoint | `whe_` |
| Event | `evt_` |
| Request | `req_` |

Clients must treat identifiers as opaque strings.

Clients must not infer:

- creation order
- database sequence
- database shard
- merchant ownership
- resource status
- creation timestamp
- internal storage format

Clients must not construct their own resource identifiers unless a specific API contract explicitly permits client-generated identifiers.

Server-generated identifiers should be returned as strings:

```json
{
  "merchantId": "mrc_01J..."
}
```

Do not expose sequential database IDs:

```json
{
  "merchantId": 42
}
```

The exact internal generation and storage mechanism will be finalized before persistence is implemented.

---

## 24. Enumeration values and statuses

Enumeration values in JSON use uppercase snake case.

Examples:

```json
{
  "status": "PENDING_VERIFICATION"
}
```

```json
{
  "status": "REQUIRES_CONFIRMATION"
}
```

```json
{
  "status": "SUCCEEDED"
}
```

Preferred:

```text
PENDING_VERIFICATION
REQUIRES_PAYMENT_METHOD
PROCESSING
SUCCEEDED
FAILED
CANCELLED
```

Avoid mixing styles:

```text
pendingVerification
PendingVerification
pending-verification
pending_verification
```

Published status values are part of the API contract.

Adding a new enum value can affect clients that assume they know every possible value.

Clients should be encouraged to handle unknown future values safely where appropriate.

A status must describe business state, not an internal implementation step.

Preferred:

```text
PROCESSING
```

Avoid:

```text
KAFKA_MESSAGE_SENT
DATABASE_ROW_INSERTED
PROVIDER_HTTP_CALL_STARTED
```

Internal processing details belong in logs, traces, or internal models.

---

## 25. Boolean fields

Boolean field names should read naturally.

Preferred:

```json
{
  "active": true,
  "livemode": false,
  "refundable": true,
  "hasMore": false
}
```

Avoid numeric or string booleans:

```json
{
  "active": 1,
  "refundable": "yes"
}
```

Use actual JSON boolean values:

```json
{
  "active": true
}
```

Avoid unclear negative names:

```json
{
  "notDisabled": true
}
```

Prefer positive names:

```json
{
  "active": true
}
```

Where a concept has more than two meaningful states, use an enum rather than multiple booleans.

Avoid:

```json
{
  "pending": false,
  "active": true,
  "suspended": false,
  "closed": false
}
```

Prefer:

```json
{
  "status": "ACTIVE"
}
```

---

## 26. Null, omitted, and empty values

The API must distinguish deliberately between:

- a field being omitted
- a field being present with `null`
- a field being present with an empty value

Example:

```json
{}
```

means the field was omitted.

```json
{
  "description": null
}
```

means the caller explicitly supplied `null`.

```json
{
  "description": ""
}
```

means the caller supplied an empty string.

These values must not be treated as equivalent without an explicit rule.

For creation requests:

- required fields must be present and valid
- optional fields may be omitted
- `null` acceptance must be documented

For patch requests, omitted and `null` may have different meanings.

Example:

```json
{}
```

may mean:

```text
Do not change the description.
```

```json
{
  "description": null
}
```

may mean:

```text
Clear the description.
```

Patch semantics must be defined before implementing partial updates.

Response fields should not be included as `null` without considering client usability and contract consistency.

The project should choose a consistent serialization policy when the first response models are implemented.

Empty collections should be returned as empty arrays rather than `null`.

Preferred:

```json
{
  "data": []
}
```

Avoid:

```json
{
  "data": null
}
```

---

## 27. Pagination

Collection endpoints that may grow must use bounded pagination.

Initial PayMesh convention:

```http
GET /api/v1/merchants?limit=20&cursor=...
```

Example response:

```json
{
  "data": [
    {
      "merchantId": "mrc_01J...",
      "businessName": "FreshBrew Cafe",
      "status": "ACTIVE"
    }
  ],
  "pagination": {
    "limit": 20,
    "nextCursor": "eyJjcmVhdGVkQXQiOi...",
    "hasMore": true
  }
}
```

When no additional page exists:

```json
{
  "data": [],
  "pagination": {
    "limit": 20,
    "nextCursor": null,
    "hasMore": false
  }
}
```

### Cursor pagination

Cursor pagination is preferred for operational resources that change frequently.

Examples:

- payments
- payment intents
- refunds
- webhook deliveries
- ledger entries

Cursor pagination is more stable than offset pagination when records are inserted while a client is paging through results.

Clients must treat cursors as opaque strings.

They must not parse, modify, or construct cursors.

### Offset pagination

Offset pagination may be used for stable reporting views when justified.

Example:

```http
GET /api/v1/reports/transactions?page=0&size=50
```

Its use should be deliberate because large offsets may become inefficient and data changes may cause duplicates or omissions between pages.

### Limits

Collection endpoints must define:

- a default limit
- a maximum limit
- behavior for invalid limits

Example direction:

```text
Default limit: 20
Maximum limit: 100
```

The exact values may vary by endpoint when justified.

Unbounded collection responses are not allowed.

Avoid:

```http
GET /api/v1/payments?limit=999999999
```

The server must enforce a maximum.

---

## 28. Filtering

Use query parameters for filtering collection resources.

Examples:

```http
GET /api/v1/payment-intents?status=SUCCEEDED
GET /api/v1/orders?customerId=cus_01J...
GET /api/v1/refunds?paymentId=pay_01J...
GET /api/v1/merchants?country=IN
```

Avoid embedding filters in path structures:

```http
GET /api/v1/payment-intents/status/SUCCEEDED
GET /api/v1/orders/customer/cus_01J...
```

Supported filters must be explicitly documented.

Unknown query parameters should not silently change behavior.

The API should either:

- reject unsupported query parameters, or
- define a consistent policy for ignoring them

The selected behavior should be consistent across endpoints.

Filter names use lower camel case:

```text
customerId
createdAfter
createdBefore
paymentStatus
```

Date and timestamp filters use ISO 8601 values.

Example:

```http
GET /api/v1/payments?createdAfter=2026-07-01T00:00:00Z
```

---

## 29. Sorting

Use query parameters for sorting.

Example:

```http
GET /api/v1/merchants?sort=createdAt
```

Descending sort may use a leading minus sign:

```http
GET /api/v1/merchants?sort=-createdAt
```

Multiple sort fields may be supported when required:

```http
GET /api/v1/payments?sort=-createdAt,amount
```

Supported sort fields must be documented.

Clients must not be allowed to sort by arbitrary database columns.

The API layer should map supported public sort names to internal query behavior.

A default stable sort should be defined for paginated endpoints.

Example:

```text
createdAt descending, followed by resource identifier descending
```

A deterministic secondary sort avoids inconsistent ordering when multiple resources share the same primary sort value.

---

## 30. Searching

Free-text search should use an explicit query parameter.

Example:

```http
GET /api/v1/merchants?search=FreshBrew
```

Search behavior must be documented.

Clients should know which fields may be searched.

Avoid giving a search parameter an undefined meaning across all fields.

For complex reporting or analytics queries, use a dedicated reporting endpoint rather than overloading operational APIs with arbitrary search logic.

---

## 31. Idempotency

Commands that could create duplicate financial or operational effects must support idempotency.

Example header:

```http
Idempotency-Key: 73f43e72-7184-4d4a-86ab-a86411cf35c2
```

Potential idempotent operations include:

- creating payment intents
- confirming payments
- creating refunds
- creating settlements
- registering merchants when duplicate requests are possible
- processing provider callbacks
- triggering webhook redelivery

Expected behavior:

1. The first request with a new key is processed.
2. The result is stored with a fingerprint of the request.
3. A repeated request with the same key and equivalent payload returns the original result.
4. The same key reused with a different payload returns a conflict.

Example conflict:

```json
{
  "type": "https://api.paymesh.dev/problems/idempotency-key-conflict",
  "title": "Idempotency key conflict",
  "status": 409,
  "code": "IDEMPOTENCY_KEY_CONFLICT",
  "detail": "The supplied idempotency key was previously used with a different request.",
  "instance": "/api/v1/payment-intents",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

The same idempotency key should be scoped appropriately.

Possible scope:

```text
authenticated merchant + endpoint or operation + idempotency key
```

Idempotency keys must not be treated as authentication credentials.

The server must define an idempotency retention period.

The exact storage and replay implementation will be introduced with the first relevant financial write operation.

Idempotency is not required for every endpoint.

Normal read operations are already safe to repeat.

---

## 32. Correlation and request identifiers

Every HTTP request will eventually have a request identifier.

Clients may supply:

```http
X-Request-Id: req_01J...
```

When absent, PayMesh generates one.

The request identifier should appear in:

- response headers
- error responses
- application logs
- distributed traces
- downstream HTTP calls
- relevant asynchronous events

Example response header:

```http
X-Request-Id: req_01J...
```

Example error response:

```json
{
  "requestId": "req_01J..."
}
```

Client-supplied request identifiers must be validated before being trusted.

Invalid, oversized, or unsafe values should be replaced with a server-generated identifier.

A request ID helps correlate activity but does not itself make an operation idempotent.

The distinction is:

```text
Request ID
→ identifies and traces one request

Idempotency key
→ prevents duplicate effects across repeated requests
```

Do not use the terms interchangeably.

---

## 33. Content negotiation and media types

JSON endpoints use:

```http
Content-Type: application/json
Accept: application/json
```

Requests containing JSON bodies must normally provide:

```http
Content-Type: application/json
```

The API should reject unsupported media types with:

```text
415 Unsupported Media Type
```

When the client requests an unsupported response format, the API may return:

```text
406 Not Acceptable
```

Custom vendor media types will not be introduced initially.

Standard JSON keeps the API simple:

```text
application/json
```

Webhook payloads may later have their own versioning or signature headers, but they will still use an explicitly documented media type.

---

## 34. Character encoding

JSON uses UTF-8.

Clients should send valid UTF-8 request bodies.

Text fields must be handled consistently and safely.

The API should not rely on platform-default character encodings.

String length validation should account for application and database limits.

Normalization rules for identifiers such as email addresses and country codes should be applied deliberately rather than altering arbitrary user-provided display text.

---

## 35. HTTP headers

PayMesh may use the following headers where appropriate:

| Header | Purpose |
|---|---|
| `Authorization` | Authentication credentials |
| `Content-Type` | Request body media type |
| `Accept` | Requested response media type |
| `Idempotency-Key` | Prevent duplicate command effects |
| `X-Request-Id` | Request correlation |
| `Location` | URL of a newly created resource |
| `Retry-After` | Suggested retry delay |
| `ETag` | Resource version identifier when optimistic HTTP concurrency is introduced |
| `If-Match` | Conditional update when optimistic HTTP concurrency is introduced |

Custom headers should be introduced only when they serve a clear cross-cutting purpose.

Business data should generally live in the JSON body rather than arbitrary custom headers.

Avoid placing sensitive values in URLs or query parameters because URLs may appear in:

- logs
- browser history
- monitoring tools
- proxy records

---

## 36. Authentication and authorization responses

Authentication will be implemented later, but its HTTP behavior should remain consistent.

Missing or invalid authentication:

```text
401 Unauthorized
```

Authenticated caller without permission:

```text
403 Forbidden
```

Authentication errors should use the standard error shape.

Example:

```json
{
  "type": "https://api.paymesh.dev/problems/authentication-required",
  "title": "Authentication required",
  "status": 401,
  "code": "AUTHENTICATION_REQUIRED",
  "detail": "Valid authentication credentials are required.",
  "instance": "/api/v1/payment-intents",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

Do not reveal whether a secret, token, API key, or internal account exists.

Authorization failures should not disclose resources belonging to another merchant.

---

## 37. Sensitive data

Requests, responses, and errors must not expose sensitive data unnecessarily.

Sensitive values may include:

- passwords
- secret API keys
- token hashes
- bearer tokens
- private signing keys
- provider credentials
- complete bank-account details
- complete payment credentials
- private authentication claims
- internal fraud reasoning
- raw personal data not required by the client

Secrets that must be displayed once should use a dedicated creation response.

Example:

```json
{
  "apiKeyId": "key_01J...",
  "secret": "pm_test_..."
}
```

The plaintext secret must not be retrievable later.

Subsequent responses may return only masked or non-sensitive metadata:

```json
{
  "apiKeyId": "key_01J...",
  "lastFour": "7F3A",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

Sensitive fields must not appear in error details.

---

## 38. Resource creation responses

A successful creation response should normally include:

- `201 Created`
- a `Location` header
- the created resource representation
- generated identifier
- initial status
- creation timestamp

Example:

```http
HTTP/1.1 201 Created
Location: /api/v1/merchants/mrc_01J...
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

Do not make the client construct the resource location from undocumented assumptions.

---

## 39. Update responses

A successful update may return:

```text
200 OK
```

with the updated resource representation.

Example:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Coffee Private Limited",
  "status": "ACTIVE",
  "updatedAt": "2026-07-18T12:30:00Z"
}
```

Alternatively, use:

```text
204 No Content
```

when no response body is useful.

The chosen behavior should be consistent for similar update endpoints.

Returning the updated resource is often useful because it confirms:

- normalized field values
- server-generated changes
- updated timestamps
- current resource state

---

## 40. Asynchronous operation responses

Long-running work may be processed asynchronously.

Example:

```http
POST /api/v1/settlements
```

Response:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/settlements/stl_01J...
```

```json
{
  "settlementId": "stl_01J...",
  "status": "PENDING",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

The response must provide a stable resource clients can query.

Avoid returning only:

```json
{
  "message": "Processing started"
}
```

That response gives the client no reliable way to track progress.

Asynchronous state should be represented through a resource and a documented state machine.

---

## 41. Retry behavior

Clients may retry requests after transient failures, but retries must be safe.

Read operations can normally be retried.

Write operations that may create duplicate effects should use an idempotency key.

Potentially retryable responses include:

```text
429 Too Many Requests
502 Bad Gateway
503 Service Unavailable
504 Gateway Timeout
```

The server may return:

```http
Retry-After: 30
```

Clients should not blindly retry every `4xx` response.

Validation and state-conflict errors normally require changing the request.

Example:

```text
422 VALIDATION_FAILED
→ correct the request

409 INVALID_PAYMENT_STATE
→ inspect current resource state

503 PROVIDER_UNAVAILABLE
→ retry later using the same idempotency key
```

The exact retry guidance for payment operations must be documented carefully when provider integration is implemented.

---

## 42. Optimistic concurrency

Optimistic concurrency may later be supported for resources that can be modified concurrently.

Possible HTTP mechanism:

```http
ETag: "merchant-version-7"
```

Client update:

```http
If-Match: "merchant-version-7"
```

When the resource has changed, the API may return:

```text
412 Precondition Failed
```

or an explicitly chosen conflict response.

This mechanism will not be implemented until a real concurrent-update use case requires it.

Database version fields must not be exposed directly as public implementation details unless intentionally mapped into an API concurrency contract.

---

## 43. Deletion and archival

Not every PayMesh resource can be deleted.

Resources containing financial or audit history will normally remain durable.

Examples that should generally not be physically deleted:

- payments
- ledger entries
- refunds
- settlements
- audit events
- webhook delivery records

Some resources may support logical deactivation or archival.

Examples:

```text
Merchant API key
Webhook endpoint
Notification preference
```

The API should model the real business operation.

For example:

```http
POST /api/v1/webhook-endpoints/{webhookEndpointId}/disable
```

may be clearer than `DELETE` when the record remains for history and can later be enabled again.

Do not expose deletion semantics that contradict the underlying business behavior.

---

## 44. Collection response consistency

All paginated collection responses should follow one consistent structure.

Example:

```json
{
  "data": [
    {
      "paymentIntentId": "pi_01J...",
      "status": "SUCCEEDED",
      "amount": 50000,
      "currency": "INR"
    }
  ],
  "pagination": {
    "limit": 20,
    "nextCursor": "eyJjcmVhdGVkQXQiOi...",
    "hasMore": true
  }
}
```

The `data` field is always an array.

When no resources match:

```json
{
  "data": [],
  "pagination": {
    "limit": 20,
    "nextCursor": null,
    "hasMore": false
  }
}
```

Do not return `404 Not Found` merely because a collection is empty.

An empty collection is normally a successful result:

```text
200 OK
```

with:

```json
{
  "data": []
}
```

---

## 45. Single-resource response consistency

A request for one resource returns the resource representation directly.

Example:

```json
{
  "paymentIntentId": "pi_01J...",
  "merchantId": "mrc_01J...",
  "amount": 50000,
  "currency": "INR",
  "status": "REQUIRES_CONFIRMATION",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

Do not inconsistently wrap single resources:

```json
{
  "data": {
    "paymentIntentId": "pi_01J..."
  }
}
```

unless PayMesh deliberately adopts a universal envelope in the future.

The initial convention is:

```text
Single resource
→ direct object

Collection
→ data and pagination wrapper

Error
→ standard problem response
```

---

## 46. Field expansion

Responses should initially return a well-defined standard representation.

Optional field expansion should not be introduced until a real client need exists.

Possible future example:

```http
GET /api/v1/payment-intents/{paymentIntentId}?expand=customer
```

Expansion increases complexity involving:

- authorization
- performance
- response size
- caching
- documentation
- circular relationships

Do not add generic expansion mechanisms prematurely.

Dedicated endpoints are often clearer:

```http
GET /api/v1/customers/{customerId}
```

---

## 47. Partial response fields

Clients will not initially be allowed to request arbitrary response fields.

Possible future syntax:

```http
GET /api/v1/merchants/{merchantId}?fields=merchantId,businessName,status
```

This feature should be introduced only when bandwidth or client-specific response shaping becomes a demonstrated requirement.

Arbitrary field selection creates additional complexity for:

- authorization
- caching
- documentation
- testing
- response stability

---

## 48. Batch operations

Batch endpoints should be introduced only for clear use cases.

Possible future example:

```http
POST /api/v1/refunds/batch
```

Batch APIs must define:

- maximum batch size
- atomic or partial-success behavior
- per-item errors
- idempotency behavior
- response ordering
- timeout behavior

Avoid creating generic batch endpoints before these semantics are defined.

For payment operations, partial success must be handled explicitly.

A batch response should not hide which items succeeded or failed.

---

## 49. Webhook API conventions

Webhook delivery is an outbound API contract.

Webhook payloads should include:

- unique event identifier
- event type
- event creation timestamp
- API version
- related resource data
- merchant context where appropriate

Example:

```json
{
  "eventId": "evt_01J...",
  "type": "payment.succeeded",
  "apiVersion": "2026-07-18",
  "createdAt": "2026-07-18T10:15:30Z",
  "data": {
    "paymentId": "pay_01J...",
    "paymentIntentId": "pi_01J...",
    "amount": 50000,
    "currency": "INR",
    "status": "SUCCEEDED"
  }
}
```

Webhook event types use lowercase dot-separated names.

Examples:

```text
merchant.created
payment_intent.created
payment.succeeded
payment.failed
refund.created
refund.succeeded
settlement.completed
```

The exact event naming convention should remain consistent once finalized.

Webhook receivers must be able to handle duplicate events.

Delivery attempts require:

- event identifiers
- signature verification
- retry behavior
- timestamp protection
- idempotent consumer handling

Detailed webhook conventions will be documented when the webhook module is designed.

---

## 50. API documentation

Every public endpoint should eventually be documented with:

- HTTP method
- URL
- purpose
- authentication requirements
- request headers
- path parameters
- query parameters
- request body
- response body
- success status codes
- error status codes
- idempotency behavior
- example requests and responses

Generated OpenAPI documentation may be introduced later.

Generated documentation does not replace thoughtful API design.

Annotations and generated schemas must reflect the actual contract.

Documentation examples must not contain:

- real credentials
- real personal information
- real financial data
- secrets

Use clearly fictional values.

---

## 51. Backward compatibility

Published API contracts must evolve deliberately.

The following changes are normally compatible:

- adding a new optional response field
- adding a new optional request field with a safe default
- adding a new endpoint
- adding a new optional query parameter
- adding a new error type for a previously undocumented failure
- increasing a maximum field length when storage allows it

The following changes are usually breaking:

- removing a field
- renaming a field
- changing a field type
- changing a resource identifier format assumption
- changing the meaning of an existing status
- changing the meaning of an existing error code
- making an optional field mandatory
- changing a success response structure
- changing a previously synchronous operation into an asynchronous one
- removing a supported enum value
- changing money units

Adding a new enum value can also affect clients that assume all values are known.

Before making a potentially breaking change:

1. identify affected clients
2. determine whether compatibility can be preserved
3. document the change
4. provide a migration path
5. introduce a new API version when necessary

---

## 52. Deprecation

Deprecated endpoints or fields should not disappear without warning.

A deprecation process may include:

- documentation updates
- response deprecation headers
- migration instructions
- a defined removal timeline
- usage monitoring

Possible future headers:

```http
Deprecation: true
Sunset: Wed, 31 Dec 2027 23:59:59 GMT
```

Deprecation behavior will be introduced when PayMesh has a published API with real consumers.

Do not keep deprecated behavior forever without a removal plan.

---

## 53. API design review checklist

Before implementing a new endpoint, review the following questions.

### Resource design

```text
Does the URL represent a resource or real business action?
Is the resource name plural?
Does the path use lowercase kebab case?
Is the nesting depth reasonable?
```

### HTTP behavior

```text
Is the HTTP method appropriate?
Is the success status code correct?
Are expected error status codes defined?
Is the operation synchronous or asynchronous?
```

### Request contract

```text
Does the endpoint use a dedicated request model?
Are required and optional fields clear?
Are field limits documented?
Are server-controlled fields excluded?
Is validation divided correctly between API and domain layers?
```

### Response contract

```text
Does the endpoint use an explicit response model?
Are internal fields hidden?
Are identifiers opaque strings?
Are timestamps UTC ISO 8601 values?
Are monetary amounts represented in minor units?
```

### Reliability

```text
Can the operation create duplicate effects?
Does it require an idempotency key?
Can it be retried safely?
Does the response provide a stable resource identifier?
```

### Security

```text
Does the response expose sensitive data?
Could the endpoint reveal another merchant's resources?
Are error messages safe?
Are secrets absent from URLs and logs?
```

### Compatibility

```text
Can the contract evolve without breaking clients?
Are enum values and error codes stable?
Is the response shape consistent with existing endpoints?
```

---

## 54. Preliminary merchant registration example

The following example demonstrates how these conventions work together.

The final Merchant API will be refined during Merchant Module Domain Discovery.

### Request

```http
POST /api/v1/merchants
Content-Type: application/json
Accept: application/json
Idempotency-Key: 73f43e72-7184-4d4a-86ab-a86411cf35c2
X-Request-Id: req_01J...
```

```json
{
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR"
}
```

### Successful response

```http
HTTP/1.1 201 Created
Location: /api/v1/merchants/mrc_01J...
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

### Validation failure

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "type": "https://api.paymesh.dev/problems/validation-failed",
  "title": "Request validation failed",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z",
  "errors": [
    {
      "field": "businessName",
      "code": "NotBlank",
      "message": "Business name is required."
    }
  ]
}
```

### Duplicate email conflict

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "type": "https://api.paymesh.dev/problems/merchant-email-already-exists",
  "title": "Merchant email already registered",
  "status": 409,
  "code": "MERCHANT_EMAIL_ALREADY_EXISTS",
  "detail": "A merchant is already registered with the supplied email address.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

### Resource retrieval

```http
GET /api/v1/merchants/mrc_01J...
Accept: application/json
X-Request-Id: req_01J...
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z"
}
```

### Resource not found

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
X-Request-Id: req_01J...
```

```json
{
  "type": "https://api.paymesh.dev/problems/merchant-not-found",
  "title": "Merchant not found",
  "status": 404,
  "code": "MERCHANT_NOT_FOUND",
  "detail": "No merchant exists with the supplied identifier.",
  "instance": "/api/v1/merchants/mrc_01J...",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

---

## 55. Conventions deliberately postponed

The following implementation details are intentionally postponed until real endpoints require them:

```text
ApiError Java model
ValidationError Java model
Global exception handler
Request ID filter
Idempotency storage
Identifier generator
Cursor encoder and decoder
Money value object
OpenAPI configuration
Authentication filters
Rate limiting
Optimistic concurrency
Custom Jackson configuration
```

Their contracts and direction are documented now.

Their implementation will be introduced alongside real use cases.

This avoids speculative infrastructure while preserving consistency.

---

## 56. Summary of required conventions

All PayMesh APIs must follow these baseline rules:

```text
Base path
→ /api/v1

Resource paths
→ plural lowercase nouns using kebab case

JSON fields
→ lower camel case

Enum values
→ uppercase snake case

Timestamps
→ ISO 8601 UTC

Money
→ integer minor units with an explicit currency

Identifiers
→ opaque prefixed strings

Creation
→ 201 Created with Location header

Retrieval
→ 200 OK

No response body
→ 204 No Content

Asynchronous acceptance
→ 202 Accepted

Malformed request
→ 400 Bad Request

Validation failure
→ 422 Unprocessable Content

State or uniqueness conflict
→ 409 Conflict

Missing resource
→ 404 Not Found

Authentication failure
→ 401 Unauthorized

Authorization failure
→ 403 Forbidden

Errors
→ one standard problem-response structure

Collections
→ bounded pagination with a data array

Duplicate-effect commands
→ idempotency support

Sensitive information
→ never exposed in responses or error details
```

The purpose of these conventions is not to make every endpoint identical.

The purpose is to make every endpoint predictable.
