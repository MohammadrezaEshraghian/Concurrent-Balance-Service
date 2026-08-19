# Concurrent Balance Service

Implementation of the **Concurrent Balance Service** coding challenge using Java 21, Spring Boot, Maven, Spring Data JPA, and an in-memory H2 database.

## Scope

The service implements the required Java API .

```java
public interface BalanceService {
    void credit(String accountId, long amount, String transactionId);
    void debit(String accountId, long amount, String transactionId);
    void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId);
    long getBalance(String accountId);
}
```

## Architecture

- `BalanceService`: required service contract.
- `BalanceServiceImpl`: validation, idempotency coordination, debit/credit/transfer rules.
- `AccountRepository`: JPA repository with `PESSIMISTIC_WRITE` row locking.
- `ProcessedTransactionRepository`: durable-in-database record of transaction IDs processed during the lifetime of the H2 database.
- `TransactionLockManager`: per-`transactionId` JVM lock to serialize simultaneous retries of the same transaction.
- H2: simple in-memory relational database used to give us real database transactions and row-level locking without external infrastructure.

## Concurrency

A global application lock is deliberately avoided. Before modifying an account, the service loads that account with `PESSIMISTIC_WRITE`. Concurrent operations targeting the same account are serialized by the database, while operations on unrelated account rows can proceed independently.

For example, two concurrent `debit(A, 700, ...)` operations against balance 1,000 cannot both observe 1,000 under the write lock. The first successful transaction leaves 300; the second then sees 300 and fails with `InsufficientFundsException`.

## Idempotency

Each successful financial operation records its `transactionId` in `processed_transactions`. Before applying an operation, the service checks whether that ID has already been processed. If so, the retry returns without applying another balance change.

Concurrent duplicate requests are serialized by a small per-transaction JVM lock (`TransactionLockManager`), not a global lock. Therefore unrelated transaction IDs do not block each other.

### Trade-off

The per-transaction lock is process-local. This implementation therefore targets the requested simple single-service challenge setup. With multiple application instances, I would replace this part with a database-native idempotency-claim strategy (or another shared coordination mechanism) so simultaneous duplicate requests arriving at different nodes have identical semantics.

The database table still has `transactionId` as its primary key, providing a final uniqueness constraint.

## Transfer atomicity and deadlock prevention

A transfer runs inside one database transaction. Both source and destination account rows are locked before either balance is changed. Any runtime failure rolls the transaction back, so a source debit cannot commit without the matching destination credit.

To reduce deadlock risk, both account rows are always locked in deterministic lexicographic `accountId` order, regardless of transfer direction. Thus `A -> B` and `B -> A` acquire locks in the same order.

Transfers from an account to itself are rejected. This is preferable to treating them as a successful no-op because it catches a likely caller error and makes idempotency/audit semantics less ambiguous.

## Validation

The implementation rejects:

- `amount <= 0`
- blank/null `transactionId`
- blank/null account IDs
- unknown accounts
- debit/transfer with insufficient funds
- same-account transfer
- long overflow when crediting a balance

A failed operation does **not** store its transaction ID, so a corrected/retried operation with that ID is allowed to execute later. This is the chosen failure semantic for the challenge.

## Tests

Integration tests exercise the service against real H2/JPA transactions, including:

- credit/debit/transfer idempotency
- insufficient-funds rollback
- same-account validation
- two concurrent debits against one account
- many simultaneous duplicates of one `transactionId`
- concurrent operations on independent accounts


## Prerequisites

- JDK 21
- Maven 3.9+
- No external database is required.
- No external services are required.

## Build and test

```bash
mvn test
```

or:

```bash
mvn clean verify
```

## Why H2?

H2 keeps the submission self-contained while still allowing transaction boundaries and pessimistic database locks to be demonstrated. It removes installation/configuration overhead from the reviewer.

Trade-off: H2 is not a production financial datastore. Locking behavior, isolation details, durability, observability, and operational characteristics would need to be revalidated against the production database (for example PostgreSQL).

## If I had more time

- Add database-specific integration tests with the intended production RDBMS using Testcontainers.
- Replace process-local idempotency coordination with a fully database-backed claim/state machine suitable for multiple service instances.
- Add metrics for lock wait times, transaction failures, and duplicate requests.
- Add an optional REST adapter while keeping the service/domain rules independent from HTTP.
