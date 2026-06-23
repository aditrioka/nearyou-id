package id.nearyou.app.appeal

/** Configurable [AppealFlow] fake for the appeal ViewModel tests — records the last submit args. */
class FakeAppealFlow(
    var statusOutcome: AppealStatusOutcome = AppealStatusOutcome.None,
    var submitOutcome: AppealSubmitOutcome = AppealSubmitOutcome.Submitted("a1"),
) : AppealFlow {
    var lastSubmittedText: String? = null
        private set
    var lastToken: String? = null
        private set
    var submitCount: Int = 0
        private set

    override suspend fun submit(
        appealText: String,
        appealToken: String,
    ): AppealSubmitOutcome {
        submitCount++
        lastSubmittedText = appealText
        lastToken = appealToken
        return submitOutcome
    }

    override suspend fun status(appealToken: String): AppealStatusOutcome {
        lastToken = appealToken
        return statusOutcome
    }
}
