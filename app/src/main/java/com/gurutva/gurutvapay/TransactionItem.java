package com.gurutva.gurutvapay;

public class TransactionItem {
    public String merchantOrderId;
    public int amount;
    public String status;
    public String orderId;
    public String transactionId;

    public TransactionItem(String merchantOrderId, int amount) {
        this.merchantOrderId = merchantOrderId;
        this.amount = amount;
        this.status = "pending";
    }
}
