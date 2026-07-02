package id.nearyou.app.data.dataexport

/**
 * Test double for [DataExportFlow] — drives a specific outcome per call and counts invocations so the
 * ViewModel / screen tests can assert behaviour + (non-)invocation without a backend (mirrors
 * `FakeAccountDeletionFlow`).
 */
class FakeDataExportFlow(
    var requestOutcome: DataExportRequestOutcome = DataExportRequestOutcome.Requested,
    var statusOutcome: DataExportStatusOutcome = DataExportStatusOutcome.None,
) : DataExportFlow {
    var requestCount: Int = 0
        private set
    var statusCount: Int = 0
        private set

    override suspend fun requestExport(): DataExportRequestOutcome {
        requestCount++
        return requestOutcome
    }

    override suspend fun fetchStatus(): DataExportStatusOutcome {
        statusCount++
        return statusOutcome
    }
}
