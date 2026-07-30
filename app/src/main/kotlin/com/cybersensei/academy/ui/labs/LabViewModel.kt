package com.cybersensei.academy.ui.labs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersensei.academy.core.common.TimeProvider
import com.cybersensei.academy.core.common.inPresentationOrder
import com.cybersensei.academy.core.database.SchoolRepository
import com.cybersensei.academy.engine.labs.AvalancheResult
import com.cybersensei.academy.engine.labs.CryptoBench
import com.cybersensei.academy.engine.labs.Inbox
import com.cybersensei.academy.engine.labs.InboxMessage
import com.cybersensei.academy.engine.labs.InspectionAnswer
import com.cybersensei.academy.engine.labs.InspectionItem
import com.cybersensei.academy.engine.labs.InspectionLab
import com.cybersensei.academy.engine.labs.Journey
import com.cybersensei.academy.engine.labs.LogHunt
import com.cybersensei.academy.engine.labs.MessageVerdict
import com.cybersensei.academy.engine.labs.PasswordStrength
import com.cybersensei.academy.engine.labs.PasswordVerdict
import com.cybersensei.academy.engine.labs.SaltDemonstration
import com.cybersensei.academy.engine.labs.Setup
import com.cybersensei.academy.engine.labs.TokenAnatomy
import com.cybersensei.academy.engine.labs.TokenReading
import com.cybersensei.academy.engine.labs.Worksite
import com.cybersensei.academy.engine.labs.judge
import com.cybersensei.academy.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One answered message in the inbox exercise. */
data class InboxAnswer(val messageId: String, val chosen: MessageVerdict) {
    fun isRight(message: InboxMessage): Boolean = message.verdict == chosen
}

data class InboxState(
    val messages: List<InboxMessage> = emptyList(),
    val index: Int = 0,
    val answers: List<InboxAnswer> = emptyList(),
    /** Set once the current message has been judged; cleared on moving to the next. */
    val revealed: InboxAnswer? = null,
) {
    val current: InboxMessage? get() = messages.getOrNull(index)
    val finished: Boolean get() = index >= messages.size
    val right: Int get() = answers.count { answer ->
        messages.firstOrNull { it.id == answer.messageId }?.let(answer::isRight) == true
    }
}

/** The shape shared by certificates, packets and manifests: look, decide, be told why. */
data class InspectionState(
    val lab: InspectionLab? = null,
    val index: Int = 0,
    val answers: List<InspectionAnswer> = emptyList(),
    val revealed: InspectionAnswer? = null,
) {
    val current get() = lab?.items?.getOrNull(index)
    val finished: Boolean get() = lab != null && index >= lab.items.size
    val right: Int get() = answers.count { answer ->
        lab?.items?.firstOrNull { it.id == answer.itemId }?.let(answer::isRight) == true
    }
}

data class JourneyState(
    val journey: Journey? = null,
    val setup: Setup = Setup(https = true, vpn = false),
)

data class WorksiteState(
    val worksite: Worksite? = null,
    val attemptId: String? = null,
    /** The corrected version is revealed on demand: seeing the break first is the lesson. */
    val defenceApplied: Boolean = false,
)

data class HuntState(
    val hunt: LogHunt? = null,
    val selected: Set<String> = emptySet(),
    val judged: Boolean = false,
)

data class CryptoState(
    val plainText: String = "Attacco all'alba",
    val caesarShift: Int = 3,
    val key: String = "chiave",
    val hashInput: String = "password",
    val avalanche: AvalancheResult? = null,
    val salted: SaltDemonstration? = null,
)

data class LabUiState(
    val lab: Lab? = null,
    // Password forge
    val password: String = "",
    val passwordVerdict: PasswordVerdict? = null,
    // Inbox
    val inbox: InboxState = InboxState(),
    // Crypto bench
    val crypto: CryptoState = CryptoState(),
    // Token anatomy
    val tokenInput: String = "",
    val tokenReading: TokenReading? = null,
    // Anomaly hunt
    val hunt: HuntState = HuntState(),
    val inspection: InspectionState = InspectionState(),
    val journey: JourneyState = JourneyState(),
    val worksite: WorksiteState = WorksiteState(),
    val loadError: String? = null,
)

/**
 * The workshops.
 *
 * One ViewModel for all of them because they share the only thing that matters here: they
 * are exercises with no score kept and no mastery recorded. A lab is where the student is
 * allowed to poke at something and be wrong without it going on their record — that freedom
 * is the reason labs work, and writing results down would quietly remove it.
 */
@HiltViewModel
class LabViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val timeProvider: TimeProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val labId: String = checkNotNull(savedStateHandle[Routes.ARG_LAB_ID])

    private val _uiState = MutableStateFlow(LabUiState())
    val uiState: StateFlow<LabUiState> = _uiState.asStateFlow()

    init {
        val lab = Lab.byId(labId)
        _uiState.value = LabUiState(lab = lab)
        when (lab) {
            Lab.SUSPICIOUS_INBOX -> loadInbox()
            Lab.ANOMALY_HUNT -> loadHunt()
            Lab.CRYPTO_BENCH -> recomputeCrypto()
            Lab.PACKET_TRACE -> loadJourney()
            Lab.WORKSITE -> loadWorksite()
            Lab.PACKET_READER -> loadInspection("/laboratori/pacchetti.json")
            Lab.CERTIFICATE_INSPECTOR -> loadInspection("/laboratori/certificati.json")
            Lab.MANIFEST_REVIEW -> loadInspection("/laboratori/manifest.json")
            else -> Unit
        }
        // Opening a workshop still counts as having shown up to study.
        viewModelScope.launch { repository.registerStudyDay() }
    }

    /**
     * The workshop has been taken all the way through: every item judged.
     *
     * The fact only, never the score — see [SchoolRepository.completeLab]. It is recorded once
     * and ignored afterwards, so replaying a lab is free and changes nothing.
     */
    private fun markCompleted() {
        viewModelScope.launch { repository.completeLab(labId) }
    }

    // --- Password forge -------------------------------------------------------------------

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            passwordVerdict = PasswordStrength.evaluate(password),
        )
    }

    // --- Suspicious inbox -----------------------------------------------------------------

    private fun loadInbox() {
        viewModelScope.launch {
            runCatching { Inbox.fromResources() }
                .onSuccess { inbox ->
                    val name = repository.profile()?.name
                    _uiState.value = _uiState.value.copy(
                        inbox = InboxState(
                            // Shuffled for the same reason quiz options are: a fixed order is
                            // a pattern, and the student would learn the sequence not the tells.
                            messages = inbox.messages.inPresentationOrder(name, "casella"),
                        ),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadError = "Non riesco ad aprire la casella.")
                }
        }
    }

    fun onInboxAnswer(verdict: MessageVerdict) {
        val state = _uiState.value.inbox
        val message = state.current ?: return
        if (state.revealed != null) return
        val answer = InboxAnswer(message.id, verdict)
        _uiState.value = _uiState.value.copy(
            inbox = state.copy(answers = state.answers + answer, revealed = answer),
        )
    }

    fun onInboxNext() {
        val state = _uiState.value.inbox
        if (state.revealed == null) return
        val advanced = state.copy(index = state.index + 1, revealed = null)
        _uiState.value = _uiState.value.copy(inbox = advanced)
        if (advanced.finished) markCompleted()
    }

    fun restartInbox() = loadInbox()

    // --- Crypto bench ---------------------------------------------------------------------

    fun onCryptoInput(plainText: String) = updateCrypto { it.copy(plainText = plainText) }

    fun onCaesarShift(shift: Int) = updateCrypto { it.copy(caesarShift = shift) }

    fun onCryptoKey(key: String) = updateCrypto { it.copy(key = key) }

    fun onHashInput(text: String) = updateCrypto { it.copy(hashInput = text) }

    private fun updateCrypto(change: (CryptoState) -> CryptoState) {
        _uiState.value = _uiState.value.copy(crypto = change(_uiState.value.crypto))
        recomputeCrypto()
    }

    private fun recomputeCrypto() {
        val crypto = _uiState.value.crypto
        _uiState.value = _uiState.value.copy(
            crypto = crypto.copy(
                avalanche = CryptoBench.avalanche(crypto.hashInput, crypto.hashInput + "!"),
                salted = CryptoBench.saltedPair(crypto.hashInput, "7f3a91", "c02be4"),
            ),
        )
    }

    fun caesarOutput(): String =
        CryptoBench.caesar(_uiState.value.crypto.plainText, _uiState.value.crypto.caesarShift)

    fun caesarAttempts(): List<Pair<Int, String>> = CryptoBench.breakCaesarByHand(caesarOutput())

    fun xorOutput(): String =
        CryptoBench.xorHex(_uiState.value.crypto.plainText, _uiState.value.crypto.key)

    // --- Token anatomy --------------------------------------------------------------------

    fun onTokenChanged(token: String) {
        _uiState.value = _uiState.value.copy(tokenInput = token)
    }

    fun readToken() {
        _uiState.value = _uiState.value.copy(
            tokenReading = TokenAnatomy.read(_uiState.value.tokenInput, timeProvider.now()),
        )
    }

    /** A token to try, so nobody has to go and find one. Signed with nothing that matters. */
    fun useExampleToken() {
        val header = base64Url("""{"alg":"HS256","typ":"JWT"}""")
        val payload = base64Url(
            """{"sub":"48219","email":"francesco@esempio.it","ruolo":"studente","iat":""" +
                "${timeProvider.now().epochSecond - 600}," +
                """"exp":${timeProvider.now().epochSecond + 900}}""",
        )
        onTokenChanged("$header.$payload.firma-di-esempio-non-verificabile")
        readToken()
    }

    private fun base64Url(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    // --- Anomaly hunt ---------------------------------------------------------------------

    private fun loadHunt() {
        viewModelScope.launch {
            runCatching { LogHunt.fromResources() }
                .onSuccess { _uiState.value = _uiState.value.copy(hunt = HuntState(hunt = it)) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadError = "Non riesco ad aprire il registro.")
                }
        }
    }

    fun onLineToggled(lineId: String) {
        val state = _uiState.value.hunt
        if (state.judged) return
        val selected = if (lineId in state.selected) state.selected - lineId else state.selected + lineId
        _uiState.value = _uiState.value.copy(hunt = state.copy(selected = selected))
    }

    fun judgeHunt() {
        _uiState.value = _uiState.value.copy(hunt = _uiState.value.hunt.copy(judged = true))
        markCompleted()
    }

    fun restartHunt() {
        _uiState.value = _uiState.value.copy(
            hunt = _uiState.value.hunt.copy(selected = emptySet(), judged = false),
        )
    }

    fun huntResult() = _uiState.value.hunt.hunt?.judge(_uiState.value.hunt.selected)

    // --- Inspection labs (certificates, packets, manifests) --------------------------------

    private fun loadInspection(path: String) {
        viewModelScope.launch {
            runCatching { InspectionLab.fromResources(path) }
                .onSuccess { lab ->
                    val name = repository.profile()?.name
                    _uiState.value = _uiState.value.copy(
                        inspection = InspectionState(lab = lab.shuffledFor(name)),
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadError = "Non riesco ad aprire il laboratorio.")
                }
        }
    }

    fun onInspectionAnswer(optionIndex: Int) {
        val state = _uiState.value.inspection
        val item = state.current ?: return
        if (state.revealed != null) return
        val answer = InspectionAnswer(item.id, optionIndex)
        _uiState.value = _uiState.value.copy(
            inspection = state.copy(answers = state.answers + answer, revealed = answer),
        )
    }

    fun onInspectionNext() {
        val state = _uiState.value.inspection
        if (state.revealed == null) return
        val advanced = state.copy(index = state.index + 1, revealed = null)
        _uiState.value = _uiState.value.copy(inspection = advanced)
        if (advanced.finished) markCompleted()
    }

    fun restartInspection() {
        val lab = _uiState.value.inspection.lab ?: return
        _uiState.value = _uiState.value.copy(inspection = InspectionState(lab = lab))
    }

    // --- Packet trace ----------------------------------------------------------------------

    private fun loadJourney() {
        viewModelScope.launch {
            runCatching { Journey.fromResources() }
                .onSuccess { _uiState.value = _uiState.value.copy(journey = JourneyState(journey = it)) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadError = "Non riesco ad aprire il percorso.")
                }
        }
    }

    fun onHttpsToggled() = updateSetup { it.copy(https = !it.https) }

    fun onVpnToggled() = updateSetup { it.copy(vpn = !it.vpn) }

    private fun updateSetup(change: (Setup) -> Setup) {
        val state = _uiState.value.journey
        _uiState.value = _uiState.value.copy(journey = state.copy(setup = change(state.setup)))
    }

    // --- Worksite --------------------------------------------------------------------------

    private fun loadWorksite() {
        viewModelScope.launch {
            runCatching { Worksite.fromResources() }
                .onSuccess { _uiState.value = _uiState.value.copy(worksite = WorksiteState(worksite = it)) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loadError = "Non riesco ad aprire il cantiere.")
                }
        }
    }

    fun onAttemptChosen(attemptId: String) {
        _uiState.value = _uiState.value.copy(
            worksite = _uiState.value.worksite.copy(attemptId = attemptId, defenceApplied = false),
        )
    }

    /**
     * Reorders the options of every item, keeping [InspectionItem.correct] pointing at the
     * same answer.
     *
     * Same reason as the interrogations: a student found within three screens that the top
     * row was always right and stopped reading. Position must carry no information, and the
     * content must not be trusted to arrange itself.
     */
    private fun InspectionLab.shuffledFor(studentName: String?): InspectionLab = copy(
        items = items.map { item ->
            val right = item.options[item.correct]
            val reordered = item.options.inPresentationOrder(studentName, item.id)
            item.copy(options = reordered, correct = reordered.indexOf(right))
        },
    )

    fun onDefenceApplied() {
        _uiState.value = _uiState.value.copy(
            worksite = _uiState.value.worksite.copy(defenceApplied = true),
        )
    }
}
