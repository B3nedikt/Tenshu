package org.tenshu.sdk.ios

import app.tenshu.control.epc.EpcPairingHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Observable state for the Control-on-iOS **EPC app-pairing host** screen ("Connect an app").
 * Swift reads the plain [statusKey] + nullable fields — never a SKIE-bridged sealed type.
 *
 * - [STATUS_STARTING] — [IosEpcPairingController.start] has been called; the host is provisioning the
 *   relay rendezvous session, so no invite URI exists yet.
 * - [STATUS_WAITING]  — the `tenshu://epc/pair#…` invite is up ([pairingUri] non-null); the host is
 *   serving the accept loop, waiting for an app (Drive) to complete the handshake.
 * - [STATUS_CONNECTED] — an app handshook; [sas] carries the 6-digit code the user confirms against
 *   the app's screen (EPC *measure 1*).
 * - [STATUS_ERROR] — provisioning/serving failed ([error] carries the message).
 */
data class EpcPairingUiState(
    val statusKey: String = STATUS_STARTING,
    val pairingUri: String? = null,
    val sas: String? = null,
    val error: String? = null,
) {
    companion object {
        const val STATUS_STARTING = "starting"
        const val STATUS_WAITING = "waiting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_ERROR = "error"
    }
}

/**
 * iOS orchestrator for the proxy-side EPC app-pairing host — the Control twin of `drive-ios`'s EPC
 * pairing client, and the surface that finally invokes [EpcPairingHost.run] on iOS (the §11.1 third
 * flow: EPC app pairing). It mirrors the Compose [`EpcPairingViewModel`] (`:control-shared`) state
 * machine, but lives in `:tenshu-sdk-ios-control/iosMain` because that VM + the [EpcPairingHost] binding sit
 * in non-iOS / above-layer modules (`:control-shared` Compose UI and `:control-shared-proxy`); an
 * additive iOS controller resolved off the real runtime avoids a cross-module move (House Rule:
 * minimal existing changes). Constructed by [ControlSurface.epcPairingController] over the host
 * resolved from the real proxy graph (RT-3); a sample host is substituted for DEBUG screenshots.
 */
class IosEpcPairingController(private val host: EpcPairingHost) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _uiState = MutableStateFlow(EpcPairingUiState())
    val uiState: StateFlow<EpcPairingUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    /** Begin hosting: provision the relay session + serve the accept loop. Idempotent while live. */
    fun start() {
        if (job != null) return
        _uiState.value = EpcPairingUiState(statusKey = EpcPairingUiState.STATUS_STARTING)
        job = scope.launch {
            try {
                host.run(
                    onInvite = { uri ->
                        _uiState.update {
                            it.copy(pairingUri = uri, statusKey = EpcPairingUiState.STATUS_WAITING)
                        }
                    },
                    onSas = { sas ->
                        _uiState.update {
                            it.copy(sas = sas, statusKey = EpcPairingUiState.STATUS_CONNECTED)
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        statusKey = EpcPairingUiState.STATUS_ERROR,
                        error = t.message ?: "pairing failed",
                    )
                }
            }
        }
    }

    /** Stop serving + reset (the user dismissed the screen); tears the relay session down. */
    fun stop() {
        job?.cancel()
        job = null
        _uiState.value = EpcPairingUiState()
    }
}
