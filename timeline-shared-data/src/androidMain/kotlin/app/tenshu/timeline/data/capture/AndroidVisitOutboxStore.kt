package app.tenshu.timeline.data.capture

import android.content.Context
import app.tenshu.outbox.FileOutboxStore
import java.io.File

/**
 * Android [VisitOutboxStore]: one file per entry under the app-private
 * `filesDir/visit_outbox/` (encrypted-at-rest per the platform posture, threat-model A35 —
 * coordinates never leave app-private storage). The mechanics live in the shared
 * `:app-outbox` [FileOutboxStore]; this keeps Timeline's directory choice + binding name.
 */
class AndroidVisitOutboxStore(context: Context) :
    VisitOutboxStore by FileOutboxStore(File(context.applicationContext.filesDir, "visit_outbox"))
