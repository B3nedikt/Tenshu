// Domain registration helper for the Tenshu Drive File Provider
// (docs/proposals/desktop-macos-integration.md §5 task 2).
//
// Registering an NSFileProviderDomain is what creates the
// ~/Library/CloudStorage/ mount + the Finder sidebar entry and routes Finder's
// requests into the .appex. The Compose/JVM Drive.app can't call NSFileProviderManager
// directly, so this small CLI is the seam; Drive runs it on every launch
// (FinderIntegration.kt).
//
//   tenshu-domain add       # register the domain (idempotent), retiring older schemes
//   tenshu-domain remove    # tear every Tenshu domain down
//
// ⚠⚠ It only works when Drive spawns it. Run from a shell it fails with providerNotFound
// (-2001 / -2014, "No provider found … for calling bundle"): the File Provider daemon
// attributes the call to the process RESPONSIBLE for the caller, and that has to be
// TenshuDrive.app.
//
// One domain for the personal realm in v1 (proposal §8 Q3); shared realms as
// additional domains are deferred.

import FileProvider
import Foundation

@main
struct DomainRegistrar {
    /// Bump when the MEANING of an item identifier changes (1 = realm path, 2 = the daemon's
    /// rename-stable id). The system keeps its own database of a domain's items keyed by
    /// identifier; fed a new scheme under the same domain, it keeps every old item beside its
    /// new twin and Finder shows "Backup" and "Backup 2". A new domain identifier starts that
    /// database from nothing, and `add` retires the domains of earlier schemes.
    static let identifierScheme = 2

    static let domain = NSFileProviderDomain(
        identifier: NSFileProviderDomainIdentifier("app.tenshu.drive.personal.\(identifierScheme)"),
        displayName: "Tenshu Drive"
    )

    static let resolvedSignalAttempts = 3

    static func main() {
        let arg = CommandLine.arguments.dropFirst().first ?? "add"
        switch arg {
        case "add":
            // Retire first, and stop if that fails: adding anyway would leave two domains with
            // one display name and two item databases — the coexistence this exists to end.
            // The old domain stays as it was and the next launch tries again.
            for stale in existingDomains() where stale.identifier != domain.identifier {
                report(wait { NSFileProviderManager.remove(stale, completionHandler: $0) },
                       action: "retire \(stale.identifier.rawValue)")
            }
            report(wait { NSFileProviderManager.add(domain, completionHandler: $0) }, action: "register")
            // Drive starting is as good a sign as any that Control is wanted now. If Finder is
            // parked in a "server unreachable" backoff — it stays parked long after Control is
            // back, and the extension can only un-park it while its own process is alive —
            // this un-parks it. Harmless when nothing is outstanding.
            //
            // ⚠ A signal the system did not accept leaves Finder parked, and with the extension
            // gone this is the only sender left. So it is retried here, and a run that never
            // got it through exits non-zero: Drive's launch loop then runs `add` again.
            if let manager = NSFileProviderManager(for: domain) {
                var failure: Error?
                for attempt in 1...resolvedSignalAttempts {
                    failure = wait {
                        manager.signalErrorResolved(NSFileProviderError(.serverUnreachable), completionHandler: $0)
                    }
                    if failure == nil || attempt == resolvedSignalAttempts { break }
                    Thread.sleep(forTimeInterval: 1)
                }
                report(failure, action: "signal reachable for", quietOnSuccess: true)
            }
        case "remove":
            for existing in existingDomains() {
                report(wait { NSFileProviderManager.remove(existing, completionHandler: $0) },
                       action: "remove \(existing.identifier.rawValue)")
            }
        default:
            FileHandle.standardError.write(Data("usage: tenshu-domain [add|remove]\n".utf8))
            exit(2)
        }
    }

    /// The domains registered for this provider. A failure to list them ends the run: `add`
    /// would otherwise see "none", skip the retirement and register a second domain.
    private static func existingDomains() -> [NSFileProviderDomain] {
        var found: [NSFileProviderDomain] = []
        var failure: Error?
        let done = DispatchSemaphore(value: 0)
        NSFileProviderManager.getDomainsWithCompletionHandler { domains, error in
            found = domains
            failure = error
            done.signal()
        }
        done.wait()
        report(failure, action: "list", quietOnSuccess: true)
        return found
    }

    private static func wait(_ body: (@escaping (Error?) -> Void) -> Void) -> Error? {
        var result: Error?
        let done = DispatchSemaphore(value: 0)
        body { error in
            result = error
            done.signal()
        }
        done.wait()
        return result
    }

    private static func report(_ error: Error?, action: String, quietOnSuccess: Bool = false) {
        if let error = error {
            FileHandle.standardError.write(Data("failed to \(action) domain: \(error)\n".utf8))
            exit(1)
        }
        if quietOnSuccess { return }
        print("domain \(action) ok: \(domain.displayName)")
    }
}
