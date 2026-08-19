package com.example.balance.service;

import com.example.balance.domain.Account;
import com.example.balance.domain.ProcessedTransaction;
import com.example.balance.exception.AccountNotFoundException;
import com.example.balance.exception.InsufficientFundsException;
import com.example.balance.exception.InvalidTransactionException;
import com.example.balance.repository.AccountRepository;
import com.example.balance.repository.ProcessedTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.ReentrantLock;

@Service
public class BalanceServiceImpl implements BalanceService {
    private final AccountRepository accountRepository;
    private final ProcessedTransactionRepository transactionRepository;
    private final TransactionLockManager transactionLockManager;
    private final TransactionTemplate transactionTemplate;

    public BalanceServiceImpl(AccountRepository accountRepository,
                              ProcessedTransactionRepository transactionRepository,
                              TransactionLockManager transactionLockManager,
                              PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionLockManager = transactionLockManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        validate(amount, transactionId);
        executeIdempotently(transactionId, "CREDIT", () -> {
            Account account = getLockedAccount(accountId);
            try {
                account.credit(amount);
            } catch (ArithmeticException ex) {
                throw new InvalidTransactionException("Balance overflow");
            }
        });
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        validate(amount, transactionId);
        executeIdempotently(transactionId, "DEBIT", () -> {
            Account account = getLockedAccount(accountId);
            if (account.getBalance() < amount) {
                throw new InsufficientFundsException(accountId);
            }
            account.debit(amount);
        });
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        validate(amount, transactionId);
        if (sourceAccountId == null || destinationAccountId == null) {
            throw new InvalidTransactionException("Account id must not be null");
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidTransactionException("Source and destination accounts must be different");
        }

        executeIdempotently(transactionId, "TRANSFER", () -> {
            String firstId = sourceAccountId.compareTo(destinationAccountId) < 0 ? sourceAccountId : destinationAccountId;
            String secondId = firstId.equals(sourceAccountId) ? destinationAccountId : sourceAccountId;

            Account first = getLockedAccount(firstId);
            Account second = getLockedAccount(secondId);
            Account source = first.getId().equals(sourceAccountId) ? first : second;
            Account destination = first.getId().equals(destinationAccountId) ? first : second;

            if (source.getBalance() < amount) {
                throw new InsufficientFundsException(sourceAccountId);
            }

            source.debit(amount);
            try {
                destination.credit(amount);
            } catch (ArithmeticException ex) {
                throw new InvalidTransactionException("Destination balance overflow");
            }
        });
    }

    @Override
    public long getBalance(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId))
                .getBalance();
    }

    private Account getLockedAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidTransactionException("Account id must not be blank");
        }
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void executeIdempotently(String transactionId, String type, Runnable operation) {
        ReentrantLock lock = transactionLockManager.acquire(transactionId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (transactionRepository.existsById(transactionId)) {
                    return;
                }
                operation.run();
                transactionRepository.save(new ProcessedTransaction(transactionId, type));
            });
        } finally {
            transactionLockManager.release(transactionId, lock);
        }
    }

    private void validate(long amount, String transactionId) {
        if (amount <= 0) {
            throw new InvalidTransactionException("Amount must be greater than zero");
        }
        if (transactionId == null || transactionId.isBlank()) {
            throw new InvalidTransactionException("Transaction id must not be blank");
        }
    }
}
