package com.flaze;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.android.billingclient.api.*;
import java.util.Collections;
import java.util.List;

/** One non-consumable lifetime upgrade. All UI callbacks run on the main thread. */
public final class BillingManager implements PurchasesUpdatedListener {
    public static final String PRODUCT_PRO = "midi_piano_pro";
    private final Activity activity;
    private final BillingListener listener;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BillingClient client;
    private ProductDetails product;
    private ProductDetails.OneTimePurchaseOfferDetails offer;
    private boolean connecting, destroyed, restoring, querying, purchasing;
    private int entitlementRevision;
    private String status = "Connecting to Google Play…";

    public BillingManager(Activity activity, BillingListener listener) {
        this.activity = activity;
        this.listener = listener;
        prefs = activity.getSharedPreferences("midi_piano_prefs", Context.MODE_PRIVATE);
        client = BillingClient.newBuilder(activity).setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection().build();
        connect();
    }

    private void ui(Runnable action) {
        handler.post(() -> { if (!destroyed && !activity.isFinishing()) action.run(); });
    }

    private void update(String message) {
        status = message;
        listener.onBillingStateChanged();
    }

    private void connect() {
        if (destroyed || connecting) return;
        connecting = true;
        update("Connecting to Google Play…");
        client.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(@NonNull BillingResult result) {
                ui(() -> {
                    connecting = false;
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        queryProduct();
                        queryPurchases();
                    } else {
                        restoring = false;
                        update("Google Play is unavailable. Tap Retry to reconnect.");
                    }
                });
            }
            @Override public void onBillingServiceDisconnected() {
                ui(() -> { connecting = false; product = null; offer = null;
                    update("Connection lost. Tap Retry to reconnect."); });
            }
        });
    }

    public void refresh() {
        if (destroyed) return;
        if (!client.isReady()) connect();
        else { queryProduct(); queryPurchases(); }
    }

    private void queryProduct() {
        if (querying) return;
        querying = true;
        product = null;
        offer = null;
        update("Loading price from Google Play…");
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_PRO).setProductType(BillingClient.ProductType.INAPP).build())).build();
        client.queryProductDetailsAsync(params, (result, details) -> ui(() -> {
            querying = false;
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                for (ProductDetails item : details.getProductDetailsList()) {
                    if (PRODUCT_PRO.equals(item.getProductId())) {
                        List<ProductDetails.OneTimePurchaseOfferDetails> offers = item.getOneTimePurchaseOfferDetailsList();
                        if (offers != null && !offers.isEmpty()) {
                            product = item;
                            offer = offers.get(0);
                        }
                    }
                }
            }
            update(offer == null ? "Upgrade unavailable. Check your Play account and connection, then Retry."
                    : "Lifetime access. One payment. No subscription.");
        }));
    }

    private void queryPurchases() {
        int revision = entitlementRevision;
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(), (result, purchases) -> ui(() -> {
            boolean requestedRestore = restoring;
            restoring = false;
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                if (requestedRestore) listener.onPurchaseError("Could not restore. Check your connection and try again.");
                listener.onBillingStateChanged();
                return; // Preserve offline entitlement on a failed query.
            }
            boolean owned = false;
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(PRODUCT_PRO)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    owned = true;
                    acknowledge(purchase, 0);
                }
            }
            if (revision == entitlementRevision) setPro(owned);
            if (requestedRestore) {
                if (owned) listener.onPurchaseRestored();
                else listener.onPurchaseError("No completed Pro purchase found on this Google Play account.");
            }
            listener.onBillingStateChanged();
        }));
    }

    public void restorePurchases() {
        if (destroyed || restoring) return;
        restoring = true;
        listener.onBillingStateChanged();
        if (client.isReady()) queryPurchases(); else connect();
    }

    public void launchPurchaseFlow() {
        if (!canPurchase()) { refresh(); return; }
        purchasing = true;
        listener.onBillingStateChanged();
        BillingFlowParams.ProductDetailsParams item = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product).setOfferToken(offer.getOfferToken()).build();
        BillingResult result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(item)).build());
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            purchasing = false;
            if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restorePurchases();
            else { product = null; offer = null; update("Could not open checkout. Tap Retry for a fresh price.");
                listener.onPurchaseError("Google Play could not open checkout. Please try again."); }
        }
    }

    @Override public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        ui(() -> {
            purchasing = false;
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (Purchase purchase : purchases) {
                    if (!purchase.getProducts().contains(PRODUCT_PRO)) continue;
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        entitlementRevision++;
                        setPro(true);
                        acknowledge(purchase, 0);
                        listener.onPurchaseSuccess();
                    } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                        listener.onPurchasePending();
                    }
                }
            } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restorePurchases();
            else if (code != BillingClient.BillingResponseCode.USER_CANCELED)
                listener.onPurchaseError("Payment could not be completed. Please try again.");
            listener.onBillingStateChanged();
        });
    }

    private void acknowledge(Purchase purchase, int attempt) {
        if (purchase.isAcknowledged() || destroyed) return;
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken()).build(), result -> ui(() -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK && attempt < 3)
                handler.postDelayed(() -> acknowledge(purchase, attempt + 1), 2000L << attempt);
            // On subsequent app resumes, queryPurchases retries any unacknowledged purchase.
        }));
    }

    private void setPro(boolean value) {
        prefs.edit().putBoolean("is_pro_user", value).apply();
        listener.onProStatusChanged(value);
    }
    public boolean isPro() { return prefs.getBoolean("is_pro_user", false); }
    public boolean canPurchase() { return !destroyed && !purchasing && !restoring && !isPro() && client.isReady() && offer != null; }
    public boolean isBusy() { return connecting || querying || purchasing; }
    public boolean isRestoring() { return restoring; }
    public String getStatus() { return status; }
    public String getProPrice() { return offer == null ? "Price unavailable" : offer.getFormattedPrice(); }
    public void destroy() { destroyed = true; handler.removeCallbacksAndMessages(null); client.endConnection(); }

    public interface BillingListener {
        void onPurchaseSuccess();
        void onPurchaseRestored();
        void onPurchasePending();
        void onPurchaseError(String message);
        void onProStatusChanged(boolean isPro);
        void onBillingStateChanged();
    }
}
