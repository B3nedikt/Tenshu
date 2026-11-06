// IClientInterface.aidl
package app.tenshu.control.android;

import android.app.PendingIntent;

interface IClientInterface {

    void callback(in byte[] request);

    void receiveFile(in ParcelFileDescriptor fileDescriptor, in byte[] dataId);

    void receiveSharedMemory(in SharedMemory memory, in byte[] dataId);

    /**
     * Hand the CALLING app a launch it may perform on Control's behalf
     * (`control-cross-app-open.md` §5c, Option B).
     *
     * The proxy is a background process and Android forbids it starting an activity
     * (measured — CO-D3), so Control builds the Intent, wraps it FLAG_IMMUTABLE, and the
     * foreground app that holds the user's gesture is the one that fires it. The ticket is
     * opaque and unalterable, which is what keeps CO-D1's "the address is the data, never
     * the app" enforced rather than merely agreed.
     */
    void receiveLaunchTicket(in PendingIntent ticket, in byte[] ticketId);

    // ⚠⚠ APPEND ONLY below this line. A method's transaction code is its position, and an app and
    // Control are installed separately: reordering or inserting re-routes an older peer's calls.

    // The revision of this interface the app implements (BinderIpc.CLIENT_REVISION). An OLDER app
    // has no such method: its stub returns false from onTransact and writes nothing, and the
    // generated proxy reads that empty reply as 0 — so 0 means "callback only", and Control must not
    // call the method below (core-client-resubscription.md §4.8).
    int ipcRevision();

    // callback(), for a message too large for one Binder transaction (~1 MB, shared by every
    // transaction in flight to this process): the same serialized bytes, in a read-only SharedMemory
    // region. Only the descriptor crosses Binder. Sent only to an app reporting revision >= 1.
    void callbackShared(in SharedMemory message);
}
