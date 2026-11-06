# Notification Service: Knowledge & Conventions

This service provides the system-wide event and alerting mechanism.

## 1. Data Model

* **Root Type**: `Notification` (`tenshu/notifications/notification`).
* **Owned here**: `Notification` (the root), `AppNotification`, and the app-lifecycle subtypes
  (`AppRegisteredNotification`, `AppRemovedNotification`), plus `DismissedRequest` /
  `DismissPendingRequest`.
* **⚠ Notification subtypes live with the service that raises them, not here.** `FileNotification`
  and its `FileAddedNotification` / `FolderAddedNotification` / `FileDeletedNotification` /
  `FileAddedBurst` family are defined in **`:file-service`**
  (`org/tenshu/core/file/notifications/`), and their DTOs are `internal` to
  **`:file-service-common`**. A new domain notification extends `Notification` from *its own*
  service module; only the app-lifecycle ones belong in this module.
* **DTO Visibility**: notification DTOs are `internal` to whichever `-common` module owns them —
  `NotificationDto` and `AppRegisteredNotificationDto` in `:notification-service-common`,
  `FileAddedNotificationDto` in `:file-service-common`.

## 2. Operations

* **`SendNotification`**: Primarily used by the Proxy to broadcast internal system changes to the
  `NOTIFICATIONS` stream.
* **`DismissPendingRequest`**: Records a `DismissedRequest` so a dismissal on one device clears
  the badge on all of them.

## 3. Relations & streaming

Two relations, both `maintained = true`:

* **`Relations.NOTIFICATIONS`** (`"$ID/notifications"`) — the notification stream.
* **`Relations.DISMISSED_REQUESTS`** (`"$ID/dismissedRequests"`) — dismissed `PendingRequest.id`s,
  replicated eagerly.

Both are derived by the **generic relation maintainer** (`core-relation-system.md` §9.2.1): the
proxy derives the element type, the array key and the element's own key from the schema, which is
why `:notification-service-proxy` registers **no observer of its own**. Don't add one.

* **Paging**: clients use `.notifications.pagingSource()` for UI display to handle high-frequency
  updates efficiently. ⚠ `pagingSource` lives in **`:core-paging`** (`PagingUtil.kt`), not in
  this module or `:core-client`.
