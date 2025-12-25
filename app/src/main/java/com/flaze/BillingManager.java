package com.flaze;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/**
 * Manages Google Play Billing for premium features.
 * One-time purchase for MIDI Piano Pro.
 */
public class BillingManager implements PurchasesUpdatedListener {

    // Product ID for the premium upgrade (configure this in Google Play Console)
    public static final String PRODUCT_PRO = "midi_piano_pro";
    private static final String TAG = "BillingManager";
    private static final String PREF_NAME = "midi_piano_prefs";
    private static final String PREF_IS_PRO = "is_pro_user";
    private static final int MAX_PRODUCT_QUERY_RETRIES = 3;
    private final Activity activity;
    private final BillingClient billingClient;
    private final BillingListener listener;
    private final SharedPreferences prefs;
    private ProductDetails proProductDetails;
    private boolean isServiceConnected = false;
    private int productQueryRetryCount = 0;

    public BillingManager(Activity activity, BillingListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        billingClient = BillingClient.newBuilder(activity).setListener(this).enablePendingPurchases(com.android.billingclient.api.PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build();

        startConnection();
    }


    private void startConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    isServiceConnected = true;
                    Log.d(TAG, "Billing service connected");
                    queryProductDetails();
                    queryExistingPurchases();
                } else {
                    Log.e(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                isServiceConnected = false;
                Log.w(TAG, "Billing service disconnected");
            }
        });
    }

    private void queryProductDetails() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_PRO).setProductType(BillingClient.ProductType.INAPP).build();

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder().setProductList(Collections.singletonList(product)).build();

        billingClient.queryProductDetailsAsync(params, (billingResult, queryProductDetailsResult) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                List<ProductDetails> productDetailsList = queryProductDetailsResult.getProductDetailsList();
                Log.d(TAG, "Product query returned " + productDetailsList.size() + " products");

                if (productDetailsList.isEmpty()) {
                    Log.w(TAG, "No products found! Checklist:\n" + "1. Product ID '" + PRODUCT_PRO + "' exists in Play Console\n" + "2. Product is set to 'Active' status\n" + "3. App is signed with release key and installed from Play Store\n" + "4. Wait a few hours after creating the product for propagation");

                    // Retry after delay (products may still be propagating)
                    if (productQueryRetryCount < MAX_PRODUCT_QUERY_RETRIES) {
                        productQueryRetryCount++;
                        Log.d(TAG, "Retrying product query in 5 seconds... (attempt " + productQueryRetryCount + "/" + MAX_PRODUCT_QUERY_RETRIES + ")");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::queryProductDetails, 5000);
                    }
                }

                for (ProductDetails details : productDetailsList) {
                    if (PRODUCT_PRO.equals(details.getProductId())) {
                        proProductDetails = details;
                        Log.d(TAG, "Pro product details loaded: " + details.getName());
                    }
                }
            } else {
                Log.e(TAG, "Failed to query product details (code " + billingResult.getResponseCode() + "): " + billingResult.getDebugMessage());
            }
        });
    }

    private void queryExistingPurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build();

        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                boolean foundPro = false;
                for (Purchase purchase : purchases) {
                    if (purchase.getProducts().contains(PRODUCT_PRO)) {
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            foundPro = true;
                            acknowledgeIfNeeded(purchase);
                        }
                    }
                }
                setProStatus(foundPro);
            }
        });
    }

    public void launchPurchaseFlow() {
        if (!isServiceConnected) {
            startConnection();
            if (listener != null) {
                listener.onPurchaseError("Billing service not ready. Please try again.");
            }
            return;
        }

        if (isPro()) {
            if (listener != null) {
                listener.onPurchaseError("You already have Pro!");
            }
            return;
        }

        if (proProductDetails == null) {
            // Product not found - likely not configured in Play Console yet
            Log.w(TAG, "Product '" + PRODUCT_PRO + "' not found. Ensure:\n" + "1. In-app product '" + PRODUCT_PRO + "' is created in Play Console under Monetization > In-app products\n" + "2. Product status is 'Active' (not Draft)\n" + "3. App is downloaded from Play Store (internal testing counts)\n" + "4. Wait 1-24 hours after creating the product for propagation\n" + "5. License testing account is added in Play Console");
            if (listener != null) {
                listener.onPurchaseError("Product not available yet. This can take up to 24 hours after setup. Please try again later.");
            }
            return;
        }

        BillingFlowParams.ProductDetailsParams productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(proProductDetails).build();

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(Collections.singletonList(productDetailsParams)).build();

        BillingResult result = billingClient.launchBillingFlow(activity, billingFlowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Launch billing flow failed: " + result.getDebugMessage());
        }
    }

    public void restorePurchases() {
        if (!isServiceConnected) {
            startConnection();
            return;
        }
        queryExistingPurchases();
        if (listener != null && isPro()) {
            listener.onPurchaseRestored();
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, List<Purchase> purchases) {
        int responseCode = billingResult.getResponseCode();

        if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User canceled purchase");
        } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            setProStatus(true);
            if (listener != null) {
                listener.onPurchaseRestored();
            }
        } else {
            if (listener != null) {
                listener.onPurchaseError(billingResult.getDebugMessage());
            }
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (!purchase.getProducts().contains(PRODUCT_PRO)) return;

        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            setProStatus(true);
            acknowledgeIfNeeded(purchase);
            if (listener != null) {
                listener.onPurchaseSuccess();
            }
        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
            if (listener != null) {
                listener.onPurchasePending();
            }
        }
    }

    private void acknowledgeIfNeeded(Purchase purchase) {
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build();

            billingClient.acknowledgePurchase(params, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged");
                }
            });
        }
    }

    public boolean isPro() {
        return prefs.getBoolean(PREF_IS_PRO, false);
    }

    private void setProStatus(boolean isPro) {
        prefs.edit().putBoolean(PREF_IS_PRO, isPro).apply();
        if (listener != null) {
            activity.runOnUiThread(() -> listener.onProStatusChanged(isPro));
        }
    }


    public String getProPrice() {
        if (proProductDetails != null && proProductDetails.getOneTimePurchaseOfferDetails() != null) {
            return proProductDetails.getOneTimePurchaseOfferDetails().getFormattedPrice();
        }
        return "$4.99"; // Fallback display price
    }

    public void destroy() {
        if (billingClient != null && billingClient.isReady()) {
            billingClient.endConnection();
        }
    }

    public interface BillingListener {
        void onPurchaseSuccess();

        void onPurchaseRestored();

        void onPurchasePending();

        void onPurchaseError(String message);

        void onProStatusChanged(boolean isPro);
    }
}

