// IProxyInterface.aidl
package app.tenshu.control.android;

interface IProxyInterface {

    byte[] call(in byte[] request);

    // Phase L2 (docs/proposals/core-background-processing.md §8): like call(),
    // but carries the client-minted taskId so the proxy registers
    // (clientId, taskId) -> requestJob in its in-flight registry. Blocks until
    // the result like call(); a concurrent cancel() runs on a separate binder
    // pool thread.
    byte[] callCancellable(in byte[] request, in String taskId);

    // Out-of-band cancel for an in-flight callCancellable task. oneway so the
    // caller (a Cancel button) doesn't block; an unknown or already-completed
    // id is a no-op on the proxy.
    oneway void cancel(in String taskId);

    void sendFile(in ParcelFileDescriptor fileDescriptor, in String contentUri);

    // ⚠⚠ APPEND ONLY below this line. A method's transaction code is its position, and an app and
    // Control are installed separately: reordering or inserting re-routes an older peer's calls.

    // Cancellable reads (docs/proposals/core-background-processing.md §6.2.1). The revision of this
    // interface Control implements (BinderIpc.kt). An OLDER Control has no such method: its stub
    // returns false from onTransact and writes nothing, and the generated proxy reads that empty
    // reply as 0 — so 0 means "cancellable reads unsupported", and a client must not call the two
    // methods below.
    int ipcRevision();

    // Like call(), but registered under (calling package, readId) so cancelRead() can stop it — its
    // job AND its synchronous row walk. No pid in the key: a oneway call does not report one, so
    // readId must be random per call (a UUID), never a per-process counter. Not a task: session-scoped, not counted for
    // foreground liveness, no sync lane. Returns an empty array when it was cancelled by the client.
    byte[] callCancellableRead(in byte[] request, in String readId);

    // Stop the in-flight callCancellableRead(readId) of the calling package. oneway: sent from a
    // coroutine that is already cancelled. An unknown or finished id is parked briefly, then dropped.
    oneway void cancelRead(in String readId);

    // Revision 2 (docs/proposals/control-background-work-off-fgs.md §5.1): callCancellable() for a
    // task the USER started. Only these may hold Control's foreground service; every other task is
    // automatic and kept alive by Control's own jobs. Call only when ipcRevision() >= 2.
    byte[] callCancellableUserInitiated(in byte[] request, in String taskId);
}
