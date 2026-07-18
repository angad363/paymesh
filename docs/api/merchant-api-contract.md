# Merchant API Contract

## Status

Draft for initial implementation.

## Purpose

This document defines the first public API contract for the PayMesh Merchant
module.

The initial API supports:

- merchant registration
- merchant retrieval

It does not yet support:

- merchant updates
- listing merchants
- activation
- suspension
- closure
- verification
- authentication
- merchant users

---

## 1. Register merchant

Registers a new business with PayMesh.

A newly registered merchant starts in:

```text
PENDING_VERIFICATION
```

### Endpoint

```http
POST /api/v1/merchants
```

### Request headers

```http
Content-Type: application/json
Accept: application/json
X-Request-Id: req_01J...
```

`X-Request-Id` is optional.

When it is not supplied, PayMesh will eventually generate one.

The first Merchant endpoint does not require an `Idempotency-Key`.

### Request body

```json
{
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR"
}
```

### Request fields

| Field | Type | Required | Rules |
|---|---|---:|---|
| `businessName` | string | yes | Not blank, maximum 200 characters |
| `email` | string | yes | Valid email format, normalized, unique |
| `country` | string | yes | Two-letter uppercase supported country code |
| `defaultCurrency` | string | yes | Three-letter uppercase supported currency code |

Clients cannot supply:

- `merchantId`
- `status`
- `createdAt`
- `updatedAt`

These values are controlled by PayMesh.

---

## 2. Successful registration response

### Status

```http
201 Created
```

### Headers

```http
Location: /api/v1/merchants/mrc_01J...
Content-Type: application/json
X-Request-Id: req_01J...
```

### Body

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z",
  "updatedAt": "2026-07-18T10:15:30Z"
}
```

### Response fields

| Field | Type | Description |
|---|---|---|
| `merchantId` | string | Opaque PayMesh merchant identifier |
| `businessName` | string | Normalized business name |
| `email` | string | Normalized merchant email |
| `country` | string | Merchant operating country |
| `defaultCurrency` | string | Merchant default currency |
| `status` | string | Merchant lifecycle status |
| `createdAt` | string | ISO 8601 UTC creation timestamp |
| `updatedAt` | string | ISO 8601 UTC most recent update timestamp |

---

## 3. Registration validation failure

### Status

```http
422 Unprocessable Content
```

### Example body

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

Potential validation failures include:

```text
businessName is missing
businessName is blank
businessName exceeds the maximum length
email is missing
email has an invalid format
country has an invalid format
defaultCurrency has an invalid format
```

---

## 4. Duplicate merchant email

### Status

```http
409 Conflict
```

### Error code

```text
MERCHANT_EMAIL_ALREADY_EXISTS
```

### Example body

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

The response must not reveal additional information about the existing
merchant.

---

## 5. Unsupported country

### Status

```http
422 Unprocessable Content
```

### Error code

```text
UNSUPPORTED_COUNTRY
```

### Example body

```json
{
  "type": "https://api.paymesh.dev/problems/unsupported-country",
  "title": "Unsupported country",
  "status": 422,
  "code": "UNSUPPORTED_COUNTRY",
  "detail": "PayMesh does not currently support merchants in the supplied country.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

The country may be syntactically valid but unsupported by PayMesh.

---

## 6. Unsupported currency

### Status

```http
422 Unprocessable Content
```

### Error code

```text
UNSUPPORTED_CURRENCY
```

### Example body

```json
{
  "type": "https://api.paymesh.dev/problems/unsupported-currency",
  "title": "Unsupported currency",
  "status": 422,
  "code": "UNSUPPORTED_CURRENCY",
  "detail": "PayMesh does not currently support the supplied currency.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

---

## 7. Unsupported country and currency combination

### Status

```http
422 Unprocessable Content
```

### Error code

```text
UNSUPPORTED_COUNTRY_CURRENCY
```

### Example body

```json
{
  "type": "https://api.paymesh.dev/problems/unsupported-country-currency",
  "title": "Unsupported country and currency combination",
  "status": 422,
  "code": "UNSUPPORTED_COUNTRY_CURRENCY",
  "detail": "The supplied default currency is not supported for the merchant's operating country.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

---

## 8. Malformed registration request

Malformed or unreadable JSON returns:

```http
400 Bad Request
```

### Error code

```text
MALFORMED_REQUEST
```

### Example body

```json
{
  "type": "https://api.paymesh.dev/problems/malformed-request",
  "title": "Malformed request",
  "status": 400,
  "code": "MALFORMED_REQUEST",
  "detail": "The request body could not be read.",
  "instance": "/api/v1/merchants",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

---

## 9. Get merchant

Retrieves one merchant by its public identifier.

### Endpoint

```http
GET /api/v1/merchants/{merchantId}
```

### Example request

```http
GET /api/v1/merchants/mrc_01J...
Accept: application/json
X-Request-Id: req_01J...
```

### Path parameter

| Parameter | Type | Description |
|---|---|---|
| `merchantId` | string | Opaque PayMesh merchant identifier |

---

## 10. Successful retrieval response

### Status

```http
200 OK
```

### Headers

```http
Content-Type: application/json
X-Request-Id: req_01J...
```

### Body

```json
{
  "merchantId": "mrc_01J...",
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR",
  "status": "PENDING_VERIFICATION",
  "createdAt": "2026-07-18T10:15:30Z",
  "updatedAt": "2026-07-18T10:15:30Z"
}
```

---

## 11. Merchant not found

### Status

```http
404 Not Found
```

### Error code

```text
MERCHANT_NOT_FOUND
```

### Example body

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

The response does not reveal internal database identifiers or query details.

---

## 12. Invalid merchant identifier format

When the supplied identifier cannot be parsed as a PayMesh merchant identifier,
the API returns:

```http
400 Bad Request
```

### Error code

```text
INVALID_MERCHANT_ID
```

### Example body

```json
{
  "type": "https://api.paymesh.dev/problems/invalid-merchant-id",
  "title": "Invalid merchant identifier",
  "status": 400,
  "code": "INVALID_MERCHANT_ID",
  "detail": "The supplied merchant identifier has an invalid format.",
  "instance": "/api/v1/merchants/123",
  "requestId": "req_01J...",
  "timestamp": "2026-07-18T10:15:30Z"
}
```

A validly formatted but unknown identifier returns `MERCHANT_NOT_FOUND`.

---

## 13. Merchant status values

The initial response contract may contain:

```text
PENDING_VERIFICATION
ACTIVE
SUSPENDED
CLOSED
```

Clients should not infer permission solely from the status string without
following the documented operation contract.

Additional statuses require compatibility review before publication.

---

## 14. Normalization behavior

Before registration:

```text
businessName
→ surrounding whitespace removed

email
→ surrounding whitespace removed
→ converted to lowercase

country
→ surrounding whitespace removed
→ converted to uppercase

defaultCurrency
→ surrounding whitespace removed
→ converted to uppercase
```

Example input:

```json
{
  "businessName": "  FreshBrew Cafe  ",
  "email": " Owner@FreshBrew.Example ",
  "country": "in",
  "defaultCurrency": "inr"
}
```

Conceptual normalized representation:

```json
{
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR"
}
```

Normalization does not make unsupported values valid.

---

## 15. Authentication

Authentication and authorization are not implemented in the first Merchant
vertical slice.

The API contract must not be interpreted as allowing anonymous merchant
registration in the final production architecture.

Security will be introduced as a separate planned capability.

Until then, the endpoints exist only in the local educational environment.

---

## 16. Idempotency

The first Merchant registration endpoint does not require:

```http
Idempotency-Key
```

Duplicate email registration is handled through:

```text
409 MERCHANT_EMAIL_ALREADY_EXISTS
```

Idempotency will be introduced first for operations where retries could create
duplicate financial effects.

---

## 17. Pagination

The first Merchant API does not include a merchant collection endpoint.

Therefore, pagination is not yet required.

When merchant listing is introduced, it must follow the PayMesh cursor
pagination convention.

---

## 18. API implementation scope

The first implementation should include only:

```text
POST /api/v1/merchants
GET /api/v1/merchants/{merchantId}
```

It should not include speculative endpoints such as:

```text
PATCH /api/v1/merchants/{merchantId}
DELETE /api/v1/merchants/{merchantId}
POST /api/v1/merchants/{merchantId}/activate
POST /api/v1/merchants/{merchantId}/suspend
GET /api/v1/merchants
```

Those endpoints require additional domain, authorization, and lifecycle
decisions.

---

## 19. Contract acceptance criteria

The initial Merchant API contract is accepted when:

```text
Registration inputs are explicitly defined.
The initial merchant status is defined.
Normalization rules are defined.
Success status and Location header are defined.
Duplicate email behavior is defined.
Validation failures use the standard error response.
Merchant retrieval behavior is defined.
Unknown merchants return MERCHANT_NOT_FOUND.
Invalid identifiers return INVALID_MERCHANT_ID.
No database representation leaks into the contract.
No authentication behavior is falsely implied.
Out-of-scope endpoints are clearly listed.
```
