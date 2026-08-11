package com.example.othello.research

const val CURRENT_RESEARCH_CONSENT_VERSION: Int = 1

object ResearchConsent {
    const val version: Int = CURRENT_RESEARCH_CONSENT_VERSION
    const val sha256: String = "d9ba89269ad4d623936f64056b82831f48e2f67e7b8798925cef866e6e593ad9"

    val statements: List<String> = listOf(
        "研究参加中に成立したオンライン対局データを、研究集合知へ提供します。",
        "個別プレイヤーの分析やスカウティング用途として公開しません。",
        "提供データは集合・統計データとして利用・公開します。",
        "研究参加をOFFにすると、それ以降に成立する対局からの新規提供を停止します。",
        "研究参加をOFFにしても、参加中にすでに提供した研究寄与は利用を継続します。",
        "アカウント削除後も、アカウントとの対応を切ったうえで、参加中に提供した研究寄与を保持する場合があります。",
        "集合研究データの閲覧は、研究参加と所定の提供条件を満たすGive-to-Get方式です。",
        "研究用rawデータを一般のアプリ利用者へ直接公開しません。",
    )

    val canonicalText: String get() = statements.joinToString("\n")
}

data class ResearchParticipationStatus(
    val participationOn: Boolean,
    val currentConsentVersion: Int,
    val agreedConsentVersion: Int?,
    val reconsentRequired: Boolean,
    val researchSubjectLinked: Boolean,
    val currentPeriodExists: Boolean,
    val currentParticipationId: String?,
    val currentPeriodStartedAtEpochMillis: Long?,
    val eligible: Boolean,
    val canViewResearchData: Boolean,
    val qualifyingGameCount: Int,
    val requiredGameCount: Int,
    val windowDays: Int,
    val collectionEnabled: Boolean,
    val collectionAllowed: Boolean,
)

interface ResearchParticipationRepository {
    suspend fun status(): ResearchParticipationStatus

    suspend fun setParticipation(
        enabled: Boolean,
        acceptedConsentVersion: Int? = null,
    ): ResearchParticipationStatus
}
