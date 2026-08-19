package com.example.balance.repository;

import com.example.balance.domain.ProcessedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedTransactionRepository extends JpaRepository<ProcessedTransaction, String> {
}
