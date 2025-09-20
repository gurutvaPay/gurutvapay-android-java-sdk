package com.gurutva.gurutvapay;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TransactionsAdapter extends RecyclerView.Adapter<TransactionsAdapter.Holder> {
    public interface Callbacks {
        void onCheckStatus(int position);
        void onDetails(int position);
    }

    private final List<TransactionItem> items;
    private final Callbacks cb;

    public TransactionsAdapter(List<TransactionItem> items, Callbacks cb) {
        this.items = items;
        this.cb = cb;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TransactionItem t = items.get(position);
        holder.tvTitle.setText(t.merchantOrderId + " — ₹" + t.amount);
        String sub = "Status: " + t.status;
        if (t.transactionId != null) sub += "\nTxn: " + t.transactionId;
        holder.tvSubtitle.setText(sub);

        holder.btnCheck.setOnClickListener(v -> cb.onCheckStatus(position));
        holder.btnDetails.setOnClickListener(v -> cb.onDetails(position));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvSubtitle;
        Button btnCheck, btnDetails;
        Holder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvSubtitle = itemView.findViewById(R.id.tvSubtitle);
            btnCheck = itemView.findViewById(R.id.btnCheckStatus);
            btnDetails = itemView.findViewById(R.id.btnDummy);
        }
    }
}
