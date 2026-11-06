package app.tenshu.control.epc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the proxy-side EPC pairing screen (proposal §10.3/§10.6). "Show QR" [start]s the
 * flow: the [host] provisions + serves the relay rendezvous session, surfacing the pairing
 * URI to render and then the SAS the user confirms against the app. All the I/O lives behind
 * [EpcPairingHost] (its impl is platform-side — `:sync-service-proxy` is not on commonMain),
 * so this VM is pure orchestration + state.
 */
class EpcPairingViewModel(
    private val host: EpcPairingHost,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EpcPairingUiState())
    val uiState: StateFlow<EpcPairingUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    /** Begin pairing: provision + serve. Idempotent while a session is live. */
    fun start() {
        if (job != null) return
        _uiState.value = EpcPairingUiState(status = EpcPairingStatus.WaitingForApp)
        job = viewModelScope.launch {
            try {
                host.run(
                    onInvite = { uri -> _uiState.update { it.copy(pairingUri = uri) } },
                    onSas = { sas ->
                        _uiState.update { it.copy(sas = sas, status = EpcPairingStatus.Connected) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(status = EpcPairingStatus.Error, error = t.message ?: "pairing failed")
                }
            }
        }
    }

    /**
     * Stop serving and reset (the user dismissed the screen). Cancels the [host] run, which
     * tears the relay session down. A fresh [start] can run a new pairing afterwards.
     */
    fun stop() {
        job?.cancel()
        job = null
        _uiState.value = EpcPairingUiState()
    }

    override fun onCleared() {
        job?.cancel()
        job = null
    }
}
