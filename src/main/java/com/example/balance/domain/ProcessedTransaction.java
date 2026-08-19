package com.example.balance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "processed_transactions")
public class ProcessedTransaction {
    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false, length = 100)
    private String transactionId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedTransaction() {}

    public ProcessedTransaction(String transactionId, String type) {
        this.transactionId = transactionId;
        this.type = type;
        this.processedAt = Instant.now();
    }

    public String getTransactionId() { return transactionId; }
    public String getType() { return type; }
    public Instant getProcessedAt() { return processedAt; }
}
