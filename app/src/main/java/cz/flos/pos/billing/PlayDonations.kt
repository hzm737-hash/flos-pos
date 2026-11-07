package cz.flos.pos.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import cz.flos.pos.DonationOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Voluntary donations through Google Play billing. The amounts are one-time in-app products created
 * in Play Console with the ids in [PRODUCT_IDS]; they are consumed right away, so the same amount can
 * be given again. A donation unlocks nothing in the app.
 *
 * Outside Google Play (the direct flos.apk download) billing is not available and no amounts are shown.
 */
class PlayDonations(context: Context) : PurchasesUpdatedListener {
    companion object {
        val PRODUCT_IDS = listOf("donate_small", "donate_medium", "donate_large")
    }

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()
    private val details = mutableMapOf<String, ProductDetails>()
    private var pendingDone: ((Boolean) -> Unit)? = null

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        val ok = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
        if (ok) consumeLeftovers()
        return ok
    }

    /** The donation amounts with prices in the user's currency, cheapest first; empty without Google Play. */
    suspend fun options(): List<DonationOption> = runCatching {
        if (!connect()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(PRODUCT_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        val found = suspendCancellableCoroutine<List<ProductDetails>> { cont ->
            client.queryProductDetailsAsync(params) { result, products ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) products.productDetailsList else emptyList()
                if (cont.isActive) cont.resume(list)
            }
        }
        found.forEach { details[it.productId] = it }
        found.sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: 0L }.map {
            DonationOption(it.productId, it.name, it.oneTimePurchaseOfferDetails?.formattedPrice ?: "")
        }
    }.getOrDefault(emptyList())

    /** Opens Google Play's payment sheet for [option] on top of [activity]. */
    fun donate(activity: Activity?, option: DonationOption, onDone: (Boolean) -> Unit) {
        val product = details[option.id]
        if (activity == null || product == null) { onDone(false); return }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build(),
        )).build()
        pendingDone = onDone
        val result = runCatching { client.launchBillingFlow(activity, params) }.getOrNull()
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingDone = null
            onDone(false)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val paid = purchases.orEmpty().filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        paid.forEach(::consume)
        val done = pendingDone
        pendingDone = null
        done?.invoke(result.responseCode == BillingClient.BillingResponseCode.OK && paid.isNotEmpty())
    }

    /** Donations paid later (e.g. a pending cash payment) are consumed the next time billing connects. */
    private fun consumeLeftovers() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach(::consume)
            }
        }
    }

    private fun consume(purchase: Purchase) {
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { _, _ -> }
    }
}
