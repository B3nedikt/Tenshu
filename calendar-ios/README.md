# calendar-ios

The SwiftUI shell for Tenshu **Calendar** on iOS (`docs/proposals/calendar-app.md` §9 — CA-8).

Native SwiftUI over the shared, Compose-free `:calendar-shared-logic` view models, reached
through the `CalendarSurface` seam on Calendar's own `TenshuSdk` framework, `:tenshu-sdk-ios-calendar`
(`docs/proposals/ios-per-app-frameworks.md` P4)
(`docs/proposals/ios-proxy-architecture.md` §9.3 — *share view models + repos, native UI*).
The twin of `contacts-ios` / `maps-ios` / `timeline-ios` / `fit-ios`.

## Route 1

Calendar is **born on route 1**: it runs the production proxy graph in its own process over the
shared App-Group replica Control writes, adopting Control's device identity and realm keys from
the shared Keychain (`CalendarLocalRuntime`). It never had an EPC tier, so there is nothing to
migrate off. Every service it reads — `:calendar-service`, `:preference-service` — is already
hosted by that graph, which is why the runtime is thin.

Without provisioning it shows the honest empty surface plus the shared
`ControlNotConnectedBanner` (ADR 0022). Calendar takes the **banner**, not the full-screen gate:
a calendar's dates exist without any data, so the screen stays legible and a wall would replace
something specific with something generic.

## What is here

**CA-8a**

- `CalendarIosApp.swift` — `@main`, the route-1 gate, the foreground rehydrate, the banner, tabs.
- `CalendarAgendaView.swift` — the agenda: a forward list grouped by day, over the shared
  `CalendarViewModel` in its `AGENDA` mode.
- `EventDetailView.swift` — one occurrence, read-only.

**CA-8b**

- `CalendarMonthView.swift` — the month grid, over UIKit's `UICalendarView` (Q7 ✅). The one place
  iOS gets a whole view for free; what is written here is the binding.
- `CalendarMonthScreen.swift` — the grid plus the selected day's events, and `CalendarScreenModel`,
  shared with the agenda tab.
- `CalendarsScreen.swift` — calendars: colours, this device's visibility, the default calendar,
  and the CA-7 subscriptions.

**CA-8c**

- `RemindersPermissionSection.swift` — the one-time "turn on reminders" row, and the route to
  Settings once it has been refused.
- Reminders are delivered by iOS itself, pre-armed through `UNCalendarNotificationTrigger`
  (`IosNotificationPresenter` in `:app-notify`). ⚠⚠ **Calendar posts them, not Control**:
  `UNUserNotificationCenter` is per app and both run the same graph, so only this app's runtime
  binds the real presenter.
- ⚠ No *Snooze* / *Dismiss* buttons on an iOS reminder yet — an Apple action needs a registered
  category plus a delegate in this shell to receive the response. A tap opens the app.

**CA-8d**

- `EventEditorScreen.swift` — create and edit, over the shared `EventEditorViewModel`: title,
  location, notes, calendar, all-day, date and times, recurrence, reminders, delete, and the
  this-occurrence-or-all prompts. Reached from `+` on the month and agenda tabs and from *Edit* on
  an event.
- ⚠ Dates cross the bridge as **integers**. SwiftUI's `DatePicker` binds a `Date`, Foundation turns
  that into components, and the view model takes them as `setStartDateFromParts` /
  `setStartTimeFromParts`. The shell never names a kotlinx-datetime constructor.

⚠ Week and day views are deliberately absent. `CalendarViewModel` supports them and the layout
maths is shared and tested, but a native time grid is a slice of its own, not a tab entry.

**CA-6c**

- `CalendarInteropSheet.swift` — `.ics` import and export for one calendar, reached from *Import or
  export…* in a calendar row's menu. Over the shared `CalendarInteropViewModel`.
- ⚠⚠ **No `CalendarFileAccess` actual, deliberately.** That `expect`/`actual` exists because
  Android's `ActivityResult` launchers may only be registered during composition; SwiftUI's
  `.fileImporter` / `.fileExporter` are plain modifiers, and `:calendar-shared-ui` does not build
  for iOS at all. The shared half of CA-6b is the view model, which takes `.ics` *text* — so this
  file is the platform layer the seam was drawn to allow, not a way around it.
- ⚠⚠ Nothing is marked busy before the import picker opens: SwiftUI is not reliably documented to
  report a cancellation on every supported iOS version, and a spinner on a callback that may never
  arrive is a sheet the user cannot leave. The export clears the busy flag before presenting its
  dialog for the same reason.
- ⚠ The security-scoped resource is not optional — a picker URL points outside the app container,
  and reading it without the scope open fails on a device while working in a simulator.

## What is not here yet

- *Open in* — handing this app a `.ics` from Files or Mail. That needs `CFBundleDocumentTypes` +
  `LSSupportsOpeningDocumentsInPlace` in `project.yml` and an open-URL handler in
  `CalendarIosApp.swift`. The shared side is already built: `CalendarInteropViewModel.openForImport`
  is the "file first, calendar second" entry order the desktop file association uses (CA-6c).
- *Snooze* / *Dismiss* buttons on a reminder — see CA-8c.

## Build

```bash
./gradlew generateCalendarIosProject     # or: cd calendar-ios && xcodegen generate

xcodebuild -project CalendarIos.xcodeproj -scheme CalendarIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/calendar-ios-dd build
```

`CalendarIos.xcodeproj` and `Info.plist` are generated and gitignored. The Xcode project lists
each Swift file individually, so adding or removing one means regenerating —
`./gradlew installGitHooks` makes that automatic on checkout and merge.
