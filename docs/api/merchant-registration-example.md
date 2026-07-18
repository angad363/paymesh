# Merchant Registration API Example

This document is a preliminary example used to validate the PayMesh API
conventions.

The final Merchant API will be designed during the Merchant module phase.

## Request

```http
POST /api/v1/merchants
Content-Type: application/json
Idempotency-Key: 73f43e72-7184-4d4a-86ab-a86411cf35c2
{
  "businessName": "FreshBrew Cafe",
  "email": "owner@freshbrew.example",
  "country": "IN",
  "defaultCurrency": "INR"
}
```

Successful response
```http request
HTTP/1.1 201 Created
Location: /api/v1/merchants/mrc_01J...
Content-Type: application/json
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


Validation failure
```http request
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json
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

Duplicate email conflict

```http request
HTTP/1.1 409 Conflict
Content-Type: application/json
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

## Why create an example before implementation?

It helps us detect design inconsistencies before writing controllers and database migrations.

For example, the example forces us to consider:

- Is email returned in the response?
- Does merchant creation require idempotency?
- Is the initial status `PENDING_VERIFICATION`?
- Is country required?
- Is currency required?

These are not all finalized yet.

The example is explicitly marked preliminary so we can refine it during domain design.

---

# Part 9: Review the conventions critically

Before committing, ask these questions:

## Coding

```text
Can a controller contain business logic? No.
Can a JPA entity be returned directly? No.
Can field injection be used? No.
Can shared become a utility dumping ground? No.
Can domain rules depend on HTTP classes? No.
API
Are resources plural nouns? Yes.
Are public APIs versioned under /api/v1? Yes.
Are timestamps UTC? Yes.
Is money represented in minor units? Yes.
Are errors structurally consistent? Yes.
Are IDs opaque to clients? Yes.
Are request and response models separate from entities? Yes.
```
