package app.tenshu.control.data

import kotlin.time.Instant

sealed interface Event {
    val id: String
    val timestamp: Instant

    sealed interface FileEvent : Event {
        data class Added(
            override val id: String,
            override val timestamp: Instant,
            val fileName: String,
            val preview: ByteArray? = null,
            val isFolder: Boolean = false
        ) : FileEvent {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Added) return false
                if (id != other.id) return false
                if (timestamp != other.timestamp) return false
                if (fileName != other.fileName) return false
                if (isFolder != other.isFolder) return false
                if (preview != null) {
                    if (other.preview == null) return false
                    if (!preview.contentEquals(other.preview)) return false
                } else if (other.preview != null) return false
                return true
            }

            override fun hashCode(): Int {
                var result = id.hashCode()
                result = 31 * result + timestamp.hashCode()
                result = 31 * result + fileName.hashCode()
                result = 31 * result + isFolder.hashCode()
                result = 31 * result + (preview?.contentHashCode() ?: 0)
                return result
            }
        }

        data class MultipleAdded(
            override val id: String,
            override val timestamp: Instant,
            val firstFileName: String,
            val otherFilesCount: Int,
            val previews: List<ByteArray> = emptyList()
        ) : FileEvent

        /**
         * [isFolder] comes from the notification's own type (`FolderDeletedNotification` vs
         * `FileDeletedNotification`), so the feed can say "Folder Deleted" without the event
         * carrying anything new on the wire. A row from a producer that predates the folder
         * subtype reads `false` — it says "File", as every delete did before.
         */
        data class Deleted(
            override val id: String,
            override val timestamp: Instant,
            val fileName: String,
            val preview: ByteArray? = null,
            val isFolder: Boolean = false
        ) : FileEvent {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Deleted) return false
                if (id != other.id) return false
                if (timestamp != other.timestamp) return false
                if (fileName != other.fileName) return false
                if (isFolder != other.isFolder) return false
                if (preview != null) {
                    if (other.preview == null) return false
                    if (!preview.contentEquals(other.preview)) return false
                } else if (other.preview != null) return false
                return true
            }

            override fun hashCode(): Int {
                var result = id.hashCode()
                result = 31 * result + timestamp.hashCode()
                result = 31 * result + fileName.hashCode()
                result = 31 * result + isFolder.hashCode()
                result = 31 * result + (preview?.contentHashCode() ?: 0)
                return result
            }
        }

        /**
         * CR-2 B4 P3: a change this realm made to [fileName] was lost, because another device deleted
         * the file and its content was freed while the change waited (`ChangeLostNotification`).
         */
        data class ChangeLost(
            override val id: String,
            override val timestamp: Instant,
            val fileName: String,
            val path: String,
        ) : FileEvent
    }

    sealed interface AppEvent : Event {
        data class Registered(
            override val id: String,
            override val timestamp: Instant,
            val appName: String
        ) : AppEvent

        data class Removed(
            override val id: String,
            override val timestamp: Instant,
            val appName: String
        ) : AppEvent
    }
}
