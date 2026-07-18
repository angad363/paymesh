# Contributing to PayMesh

## Development principles

Changes should be:

- small and focused
- tested where appropriate
- documented when they introduce architectural decisions
- written using clear and descriptive names
- free of committed credentials or secrets

## Branch naming

Use descriptive branch names:

feature/merchant-registration
feature/payment-intent
fix/duplicate-payment
test/ledger-balancing
docs/payment-flow
chore/project-setup

## Commit messages

Use the following style:

feat(merchant): add merchant registration
fix(payment): prevent duplicate confirmation
test(ledger): verify balanced journal entries
docs(architecture): document payment flow
chore(project): initialize repository

### Pull requests

A pull request should:

solve one focused problem
explain what changed
explain why the change was needed
include tests where applicable
avoid unrelated formatting changes