package id.nearyou.app.infra.revenuecat

import com.revenuecat.purchases.kmp.models.PurchasesErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** premium-entitlement-lifecycle: the store-failure → [PurchaseResult] mapping (the SDK itself is manual-verify only). */
class TransactionFailureResultTest {
    @Test
    fun userCancellationIsCancelledNotAnError() {
        assertEquals(PurchaseResult.Cancelled, transactionFailureResult(true, PurchasesErrorCode.PurchaseCancelledError, "x"))
    }

    @Test
    fun paymentPendingIsPending() {
        assertEquals(PurchaseResult.Pending, transactionFailureResult(false, PurchasesErrorCode.PaymentPendingError, "pending"))
    }

    @Test
    fun anyOtherFailureIsARetryableError() {
        assertEquals(
            PurchaseResult.Error("store down"),
            transactionFailureResult(false, PurchasesErrorCode.StoreProblemError, "store down"),
        )
    }
}
