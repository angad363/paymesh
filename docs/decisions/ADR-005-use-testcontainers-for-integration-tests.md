# ADR-005: Run integration tests against Testcontainers, not a developer database

## Status

Accepted

## Context

Once the merchant repository was backed by JPA, every test that boots the Spring
context required a live PostgreSQL. Hibernate runs with `ddl-auto=validate`, so
even a test unrelated to persistence fails at startup without a database whose
schema matches the mapped entities.

Pointing those tests at the developer's local database created three problems. A
fresh clone could not build. Continuous integration had no database at all. And
tests wrote real rows: `MerchantControllerTest` registers merchants with fixed
email addresses, so the unique constraint on `merchants.email` failed on the
second run.

## Decision

Tests that load the Spring context run against a throwaway PostgreSQL container.

A single `TestcontainersConfiguration` in the test sources declares a
`PostgreSQLContainer` bean annotated with `@ServiceConnection`, which overrides
the datasource properties with the container's own URL and credentials. Test
classes opt in with `@Import(TestcontainersConfiguration.class)`.

The container image tag tracks the PostgreSQL version the application is
deployed against, so behaviour under test matches production.

Tests that write are additionally annotated `@Transactional` so each one rolls
back, keeping fixed test data from colliding across runs.

## Consequences

A clone needs only Docker. CI needs no database service. Flyway migrates an
empty container on every run, so the migrations themselves are re-proved
continuously rather than assumed.

Accepted cost: Docker becomes a hard prerequisite for the test suite, and
container startup adds roughly a minute to a cold run. Spring's context cache
means one container per distinct context configuration, not per test class.

Unit tests for domain and application code remain plain JUnit with no Spring and
no container, and must stay that way — the container is for the boundary, not
for business rules.
