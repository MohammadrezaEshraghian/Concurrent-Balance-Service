package com.example.balance.service;

import com.example.balance.domain.Account;
import com.example.balance.exception.InsufficientFundsException;
import com.example.balance.exception.InvalidTransactionException;
import com.example.balance.repository.AccountRepository;
import com.example.balance.repository.ProcessedTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BalanceServiceIntegrationTest {
    @Autowired BalanceService balanceService;
    @Autowired AccountRepository accountRepository;
    @Autowired ProcessedTransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(new Account("A", 1_000));
        accountRepository.save(new Account("B", 500));
    }

    @Test
    void creditIsIdempotent() {
        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");
        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
    }

    @Test
    void debitIsIdempotent() {
        balanceService.debit("A", 100, "TX-2");
        balanceService.debit("A", 100, "TX-2");
        assertThat(balanceService.getBalance("A")).isEqualTo(900);
    }

    @Test
    void transferIsIdempotentAndAtomic() {
        balanceService.transfer("A", "B", 300, "TX-3");
        balanceService.transfer("A", "B", 300, "TX-3");
        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
    }

    @Test
    void insufficientDebitDoesNotChangeBalance() {
        assertThatThrownBy(() -> balanceService.debit("A", 1_200, "TX-4"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
        assertThat(transactionRepository.existsById("TX-4")).isFalse();
    }

    @Test
    void rejectsSameAccountTransfer() {
        assertThatThrownBy(() -> balanceService.transfer("A", "A", 100, "TX-5"))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void onlyOneOfTwoConcurrentDebitsCanSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String txId = "CONCURRENT-DEBIT-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    balanceService.debit("A", 700, txId);
                    success.incrementAndGet();
                } catch (InsufficientFundsException ex) {
                    insufficient.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(balanceService.getBalance("A")).isEqualTo(300);
    }

    @Test
    void concurrentDuplicateTransactionIsAppliedOnce() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                balanceService.credit("A", 100, "SAME-TX");
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentOperationsOnIndependentAccountsRemainCorrect() throws Exception {
        int operationsPerAccount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < operationsPerAccount; i++) {
            int n = i;
            futures.add(pool.submit(() -> { start.await(); balanceService.credit("A", 1, "A-" + n); return null; }));
            futures.add(pool.submit(() -> { start.await(); balanceService.credit("B", 1, "B-" + n); return null; }));
        }

        start.countDown();
        for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(balanceService.getBalance("B")).isEqualTo(600);
    }
}
