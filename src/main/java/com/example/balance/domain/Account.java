package com.example.balance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private String id;

    @Column(nullable = false)
    private long balance;

    protected Account() {}

    public Account(String id, long balance) {
        this.id = id;
        this.balance = balance;
    }

    public String getId() { return id; }
    public long getBalance() { return balance; }

    public void credit(long amount) {
        balance = Math.addExact(balance, amount);
    }

    public void debit(long amount) {
        balance = Math.subtractExact(balance, amount);
    }
}
