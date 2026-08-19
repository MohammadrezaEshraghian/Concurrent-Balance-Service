package com.example.balance.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
class TransactionLockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    ReentrantLock acquire(String transactionId) {
        ReentrantLock lock = locks.computeIfAbsent(transactionId, ignored -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    void release(String transactionId, ReentrantLock lock) {
        try {
            lock.unlock();
        } finally {
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                locks.remove(transactionId, lock);
            }
        }
    }
}
