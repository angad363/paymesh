# PayMesh Java Coding Conventions

## Purpose

This document defines the baseline Java coding and design conventions used
throughout the PayMesh backend.

These rules exist to keep modules understandable, testable, and consistent as
the application grows.

They are guidelines rather than substitutes for engineering judgment. Any
intentional exception should have a clear reason.

---

## 1. Package organization

PayMesh uses package-by-feature.

Business code is grouped by capability:

```
com.paymesh
├── merchant
├── customer
├── order
├── payment
├── ledger
└── shared
```

Global technical packages such as the following must not be introduced:

```
com.paymesh.controller
com.paymesh.service
com.paymesh.repository
com.paymesh.entity
com.paymesh.dto
com.paymesh.util
```

A business module may eventually contain:
```
merchant/
├── api/
├── application/
├── domain/
└── infrastructure/
```

## 2. Class responsibilities

Each class should have one clear reason to change.

Examples:

- A controller handles HTTP concerns.
- An application service coordinates a use case.
- A domain object protects business invariants.
- A repository provides persistence access.
- A mapper converts between representations.

A controller must not contain business rules.
A repository must not decide business behavior.
A request model must not be used as the domain model.

## 3. Naming

Names should describe intent rather than implementation details.

Preferred:
```
MerchantRegistrationService
RegisterMerchantRequest
MerchantResponse
MerchantRepository
PaymentConfirmationService
```

Avoid vague names:
```
Manager
Helper
Processor
CommonService
Utils
Data
Object
```

Use verbs for operations:
```
registerMerchant
confirmPayment
issueRefund
calculateAvailableBalance
```

Use nouns for types and state:
```
Merchant
PaymentIntent
Refund
LedgerEntry
```

Boolean names should read naturally:
```
isActive
hasSufficientBalance
canBeRefunded
```

Avoid:
```
activeFlag
checkStatus
value1
data
temp
```

## 4. Dependency injection

Use constructor injection.

Preferred:
```java
class MerchantApplicationService {

    private final MerchantRepository merchantRepository;

    MerchantApplicationService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }
}
```
Do not use field injection:
```java
@Autowired
private MerchantRepository merchantRepository;
```

Constructor injection:

- makes dependencies explicit 
- supports immutability 
- simplifies unit testing 
- prevents partially initialized objects 
- works without a Spring container in tests

When a class has only one constructor, `@Autowired` is unnecessary.

## 5. Immutability
Prefer immutable request models, response models, commands, events, and value
objects.

Java records may be used for simple immutable data carriers:
```java
public record RegisterMerchantRequest(
        String businessName,
        String email
) {
}
```

Entities and domain aggregates may require controlled state changes, but fields
should not be exposed through unrestricted setters.

Avoid generated setters that allow invalid state transitions.

Preferred:
```
payment.markSucceeded(providerReference);
```
Avoid:
```
payment.setStatus(PaymentStatus.SUCCEEDED);
```

The first form expresses a business operation and can enforce its rules.


## 6. Null handling

Public contracts should make nullability clear.

Required API fields must be validated.

Methods should not return `null` collections.

Preferred:

```
return List.of();
```

Avoid:

```java
 return null;
```

`Optional` may be used as a repository or query return type when a value may be absent:

```java
Optional<Merchant> findById(MerchantId merchantId);
```

Do not use `Optional`:

- as an entity field
- as a request field
- as a method parameter
- only to avoid thinking about nullability

Using `Optional` should communicate that the absence of a value is an expected outcome.

It should not be used merely to wrap every potentially nullable value.

---

## 7. Exception handling

Do not throw generic exceptions for expected business failures.

Avoid:

```java
throw new RuntimeException("Invalid state");
```

Prefer meaningful domain or application exceptions:

```java
throw new MerchantNotFoundException(merchantId);
throw new DuplicateMerchantEmailException(email);
throw new InvalidPaymentStateException(paymentId, currentStatus);
```

Exceptions should describe the business failure.

HTTP conversion belongs at the API boundary, not in domain code.

For example, the domain should not throw an exception containing an HTTP status:

```java
throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Payment cannot be confirmed"
);
```

Instead, the domain or application layer should throw a business-specific exception:

```java
throw new InvalidPaymentStateException(
        paymentId,
        currentStatus
);
```

The API layer can then translate that exception into the appropriate HTTP response.

This separation ensures that:

- domain logic does not depend on HTTP
- the same business logic can be used outside a REST controller
- error responses remain consistent
- exception handling can be tested independently

Exceptions must not expose:

- database queries
- SQL error messages
- internal class names
- stack traces
- authentication secrets
- infrastructure details

Unexpected technical failures should be logged internally and returned to clients using a safe generic error response.

---

## 8. Logging

Use a structured logging facade.

For Spring Boot applications, use SLF4J rather than writing directly to standard output.

Avoid:

```java
System.out.println("Merchant created");
```

Prefer:

```java
log.info("Merchant registration completed merchantId={}", merchantId);
```

Never log:

- passwords
- authentication tokens
- API secrets
- private signing keys
- full payment credentials
- complete bank-account information
- sensitive personal information
- raw authorization headers
- database connection strings containing credentials

Logs should include useful diagnostic context such as:

- request ID
- correlation ID
- merchant ID
- payment ID
- refund ID
- operation
- outcome
- failure category

Avoid logs that provide no diagnostic value:

```java
log.info("Inside method");
log.info("Here");
log.info("Success");
```

Preferred:

```java
log.info(
        "Merchant registration completed merchantId={} requestId={}",
        merchantId,
        requestId
);
```

For failures, include relevant identifiers without exposing sensitive data:

```java
log.warn(
        "Payment confirmation rejected paymentIntentId={} currentStatus={} requestId={}",
        paymentIntentId,
        currentStatus,
        requestId
);
```

Use log levels intentionally:

| Level | Purpose |
|---|---|
| `TRACE` | Extremely detailed diagnostic information |
| `DEBUG` | Development and troubleshooting details |
| `INFO` | Important normal business or operational events |
| `WARN` | Unexpected situations from which the system can recover |
| `ERROR` | Failures requiring investigation |

Do not log normal expected validation failures as system errors.

For example, an invalid request from a client is normally not an `ERROR` unless it indicates a system malfunction.

Avoid logging the same exception in several layers.

A failure should normally be logged once at the boundary where sufficient context is available.

Do not rely on logs as the source of truth for financial state.

Authoritative financial state belongs in:

- database records
- ledger entries
- audit records
- durable domain events

Logs are diagnostic evidence, not financial records.

---

## 9. Comments and documentation

Comments should explain why something exists, not restate what the code already says.

Avoid:

```java
// Set status to active
merchant.setStatus(ACTIVE);
```

The comment adds no information that is not already visible in the code.

A useful comment explains a non-obvious decision:

```java
// Activation is deferred until onboarding checks have completed.
merchant.markPendingVerification();
```

Prefer expressive code over explanatory comments.

Instead of:

```java
// Check whether the payment can be refunded
if (payment.getStatus() == SUCCEEDED
        && payment.getRefundedAmount() < payment.getCapturedAmount()) {
    // ...
}
```

Prefer:

```java
if (payment.canBeRefunded()) {
    // ...
}
```

Comments may be appropriate for:

- non-obvious business decisions
- important trade-offs
- temporary compatibility behavior
- external-system limitations
- complex algorithms
- security-related reasoning
- financial invariants

Comments should not be used to excuse unclear code.

Avoid commented-out code:

```java
// paymentRepository.delete(payment);
// oldPaymentService.process(payment);
```

Git already preserves previous versions.

Delete unused code rather than commenting it out.

Use Javadoc where it adds meaningful contract information, especially for:

- public module APIs
- reusable value objects
- important domain operations
- package boundaries
- behavior that is not obvious from method signatures

Avoid Javadoc that only repeats the method name.

Poor:

```java
/**
 * Gets the merchant.
 *
 * @return the merchant
 */
Merchant getMerchant();
```

Useful:

```java
/**
 * Returns the merchant visible to the authenticated tenant.
 *
 * @throws MerchantNotFoundException when the merchant does not exist
 *         or is not accessible to the current tenant
 */
Merchant findAccessibleMerchant(MerchantId merchantId);
```

---

## 10. Method design

Methods should:

- perform one coherent operation
- use descriptive names
- have a small number of parameters
- avoid hidden side effects
- return meaningful results
- operate at one consistent level of abstraction

When a method requires many related parameters, introduce a command or value object.

Avoid:

```java
register(
        String name,
        String email,
        String phone,
        String country,
        String currency,
        boolean active
);
```

Prefer:

```java
registerMerchant(RegisterMerchantCommand command);
```

A command groups the inputs belonging to one use case:

```java
public record RegisterMerchantCommand(
        String businessName,
        String email,
        String country,
        String defaultCurrency
) {
}
```

Method names should reveal intent.

Avoid:

```java
process();
handle();
execute();
doWork();
manage();
```

Prefer:

```java
registerMerchant();
confirmPayment();
issueRefund();
calculateAvailableBalance();
deliverWebhook();
```

Generic names such as `process` may be acceptable only when the containing type already provides clear context.

For example:

```java
paymentConfirmationProcessor.process(command);
```

However, a more descriptive method is usually preferable:

```java
paymentConfirmationService.confirm(command);
```

Avoid boolean parameters when their meaning is unclear:

```java
registerMerchant(command, true);
```

The caller cannot tell what `true` represents.

Prefer a meaningful type or separate operation:

```java
registerMerchant(command, VerificationMode.DEFERRED);
```

or:

```java
registerPendingMerchant(command);
```

Avoid methods that both modify state and unexpectedly return unrelated information.

A method's side effects should be clear from its name and responsibility.

Keep method bodies focused.

When a method performs several distinct tasks, extract behavior based on responsibility rather than merely reducing line count.

---

## 11. Mapping between layers

API request and response models must not be reused as persistence entities.

The intended flow is:

```text
HTTP request
→ API request model
→ application command
→ domain model
→ persistence representation
```

A response follows the reverse boundary:

```text
domain/application result
→ API response model
→ HTTP response
```

Each representation has a different responsibility.

### API request model

Represents data supplied by an external client.

It may contain boundary validation:

```java
public record RegisterMerchantRequest(
        @NotBlank
        @Size(max = 200)
        String businessName,

        @NotBlank
        @Email
        String email
) {
}
```

### Application command

Represents the input to a use case.

```java
public record RegisterMerchantCommand(
        String businessName,
        String email
) {
}
```

### Domain model

Represents business state and protects business invariants.

```java
final class Merchant {

    private MerchantStatus status;

    void activate() {
        if (status != MerchantStatus.PENDING_VERIFICATION) {
            throw new InvalidMerchantStateException(status);
        }

        status = MerchantStatus.ACTIVE;
    }
}
```

### Persistence representation

Represents how data is stored.

This may later be a JPA entity:

```java
@Entity
@Table(name = "merchants")
class MerchantJpaEntity {
    // Persistence-specific fields and mappings
}
```

### API response model

Represents the stable external contract:

```java
public record MerchantResponse(
        String merchantId,
        String businessName,
        String status,
        Instant createdAt
) {
}
```

Do not return JPA entities directly from controllers.

Doing so can:

- expose internal database fields
- couple the API to the persistence model
- trigger lazy-loading errors
- create circular serialization
- make schema changes accidentally break clients
- expose fields that should remain private

Initially, mappings should be explicit and easy to understand.

Example:

```java
static RegisterMerchantCommand toCommand(
        RegisterMerchantRequest request
) {
    return new RegisterMerchantCommand(
            request.businessName(),
            request.email()
    );
}
```

Do not introduce a mapping framework until manual mapping becomes repetitive enough to justify the additional abstraction.

For small mappings, explicit Java code is easier to debug and review.

---

## 12. Testing expectations

Business rules should be testable without starting the complete Spring application context.

Use the smallest appropriate type of test.

### Unit tests

Use unit tests for:

- domain behavior
- state transitions
- value objects
- calculations
- business policies
- application services with mocked or fake boundaries

Examples:

```java
void rejectsRegistrationWhenBusinessNameIsBlank()
void preventsRefundAboveCapturedAmount()
void rejectsConfirmationWhenPaymentAlreadySucceeded()
```

Unit tests should normally run without:

- Spring Boot
- PostgreSQL
- Kafka
- Redis
- network access

### Integration tests

Use integration tests for boundaries involving real infrastructure, such as:

- JPA mappings
- repositories
- Flyway migrations
- PostgreSQL constraints
- transaction behavior
- Kafka serialization
- Redis integration

Database integration tests should eventually use Testcontainers rather than an in-memory database when PostgreSQL-specific behavior matters.

### API tests

Use API tests for:

- HTTP paths
- request validation
- JSON serialization
- response status codes
- response headers
- error response structure
- authentication and authorization

### Architecture tests

Use architecture tests for:

- module boundaries
- forbidden package dependencies
- domain independence from infrastructure
- package-by-feature conventions

Avoid loading the entire application context for every small rule test.

A test should start only the infrastructure required to verify its behavior.

Test method names should describe expected behavior.

Preferred:

```java
void rejectsRegistrationWhenBusinessNameIsBlank()
void returnsExistingResponseForRepeatedIdempotencyKey()
void preventsRefundAboveCapturedAmount()
```

Avoid:

```java
void testMerchant()
void test1()
void successTest()
```

Tests should generally follow this structure:

```text
Given
→ When
→ Then
```

Example:

```java
@Test
void preventsRefundAboveCapturedAmount() {
    Payment payment = succeededPaymentWithCapturedAmount(10_000);

    assertThatThrownBy(() -> payment.refund(12_000))
            .isInstanceOf(RefundAmountExceededException.class);
}
```

Tests should verify observable behavior rather than internal implementation details.

Avoid asserting that private methods were called or tying tests unnecessarily to the internal structure of a class.

A refactor that preserves behavior should not require rewriting most tests.

---

## 13. Framework boundaries

Spring annotations should primarily be used at application and infrastructure boundaries.

Domain rules should not require a running Spring container.

The domain should not need annotations such as:

```java
@Component
@Service
@Autowired
@RestController
```

to express business behavior.

A domain object should be constructible and testable using ordinary Java:

```java
PaymentIntent paymentIntent = PaymentIntent.create(
        paymentIntentId,
        merchantId,
        money
);
```

Avoid making every class a Spring bean.

A class should become a Spring-managed bean because it participates in application wiring, lifecycle management, or infrastructure integration—not simply because adding `@Component` is convenient.

Typical Spring-managed components include:

- REST controllers
- application services
- repository adapters
- configuration classes
- message consumers
- scheduled jobs
- infrastructure clients

Typical classes that may not need to be Spring beans include:

- entities
- value objects
- commands
- responses
- domain services without external dependencies
- pure policies
- mappers implemented as static or ordinary objects

Framework-specific types must not leak deeply into the domain.

Avoid domain methods returning:

```java
ResponseEntity<PaymentResponse>
```

The domain should return business values or domain objects.

The controller is responsible for converting those results into HTTP responses.

Similarly, domain code should not depend on:

- HTTP status codes
- servlet requests
- JPA repository interfaces
- Kafka records
- Redis templates
- JSON nodes
- provider-specific HTTP payloads

This separation improves:

- testability
- portability
- readability
- future service extraction
- resistance to framework changes

---

## 14. Lombok

Lombok will not be added initially.

The project will use explicit constructors and methods while the domain model is still being learned.

This keeps generated behavior visible and avoids unrestricted generated setters.

Avoid using Lombok annotations such as:

```java
@Data
@Setter
@NoArgsConstructor
@AllArgsConstructor
```

on domain entities without carefully understanding the generated behavior.

For example, `@Data` can generate public setters for every field, allowing callers to bypass business rules:

```java
payment.setStatus(PaymentStatus.SUCCEEDED);
```

A domain operation should instead express intent:

```java
payment.markSucceeded(providerReference);
```

Explicit code is valuable during the early stages because it makes:

- object construction visible
- mutability visible
- dependencies visible
- business operations visible
- generated equality behavior unnecessary to guess

Java records may be used for immutable data carriers where appropriate:

```java
public record RegisterMerchantCommand(
        String businessName,
        String email
) {
}
```

The Lombok decision may be revisited later for narrowly scoped boilerplate reduction.

Any future use should avoid hiding domain behavior or generating unrestricted mutation.

---

# Why these coding rules matter

The most important rule here is not formatting.

It is this:

> Framework code, application coordination, domain rules, and persistence code must not collapse into one class.

A common beginner controller looks like:

```java
@PostMapping("/merchants")
public ResponseEntity<?> createMerchant(
        @RequestBody Map<String, Object> request
) {
    // Validate fields
    // Check whether email already exists
    // Create database entity
    // Assign merchant status
    // Save entity
    // Send notification
    // Build response
    // Handle exceptions
}
```

This controller has too many responsibilities.

It is responsible for:

- HTTP handling
- request parsing
- validation
- business rules
- persistence
- state transitions
- side effects
- response mapping
- exception handling

That makes it difficult to:

- test
- reuse
- change
- reason about
- review
- extract into another service later

A better responsibility flow is:

```text
Controller
→ converts HTTP input into a command

Application service
→ coordinates the registration use case

Domain model
→ protects merchant business rules

Repository
→ persists and retrieves merchant state

API mapper
→ converts the result into an HTTP response
```

Example:

```java
@RestController
class MerchantController {

    private final MerchantApplicationService merchantApplicationService;

    MerchantController(
            MerchantApplicationService merchantApplicationService
    ) {
        this.merchantApplicationService = merchantApplicationService;
    }

    @PostMapping("/api/v1/merchants")
    ResponseEntity<MerchantResponse> registerMerchant(
            @Valid @RequestBody RegisterMerchantRequest request
    ) {
        RegisterMerchantResult result =
                merchantApplicationService.register(
                        RegisterMerchantCommand.from(request)
                );

        MerchantResponse response = MerchantResponse.from(result);

        return ResponseEntity
                .created(URI.create(
                        "/api/v1/merchants/" + response.merchantId()
                ))
                .body(response);
    }
}
```

The controller handles HTTP concerns.

The application service coordinates the use case:

```java
class MerchantApplicationService {

    private final MerchantRepository merchantRepository;

    MerchantApplicationService(
            MerchantRepository merchantRepository
    ) {
        this.merchantRepository = merchantRepository;
    }

    RegisterMerchantResult register(
            RegisterMerchantCommand command
    ) {
        // Coordinate the use case.
        // Business behavior remains inside domain objects where appropriate.
        throw new UnsupportedOperationException(
                "Example only"
        );
    }
}
```

The domain object protects its own state:

```java
final class Merchant {

    private MerchantStatus status;

    void activate() {
        if (status != MerchantStatus.PENDING_VERIFICATION) {
            throw new InvalidMerchantStateException(status);
        }

        status = MerchantStatus.ACTIVE;
    }
}
```

The repository owns persistence access:

```java
interface MerchantRepository {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(MerchantId merchantId);

    boolean existsByEmail(EmailAddress email);
}
```

This structure follows separation of concerns.

It also helps PayMesh evolve from a modular monolith into independently deployed services later because business boundaries are visible before deployment boundaries are introduced.

Clean code does not mean creating the maximum number of classes.

It means giving each important responsibility a clear owner while avoiding unnecessary abstractions.

The project should remain as simple as possible, but no simpler than the business problem allows.


