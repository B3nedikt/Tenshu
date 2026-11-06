package app.tenshu.control.integration

import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.TString
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.core.onFailure
import org.tenshu.file.client.fileService
import org.tenshu.notifications.client.notificationService

class HomeScreenIntegrationTest {

    @Before
    fun setup() {
        startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @Test
    fun testAddFolderEmitsNotification() = runTest {
        val client = GlobalContext.get().get<TenshuClient>()

        // Start listening BEFORE the action
        val notificationDeferred = async {
            client.notificationService.notifications.flow()
                .filter { res ->
                    res.getOrNull()?.toList()?.any { item ->
                        val typeId = if (item is Entity<*>) item.type.get() else item.id
                        typeId == FolderAddedNotification.ID &&
                                item.getField<TString>(FileAddedNotification.Fields.PATH)
                                    .get().contains("test_folder")
                    } == true
                }
                .first()
        }

        // Trigger action
        client.fileService.defaultFileSystem.createFolder("/test_folder").onFailure { error(it) }

        // Wait for result
        notificationDeferred.await()
    }

    @Test
    fun uploadFileEmitsNotification() = runTest {
        val client = GlobalContext.get().get<TenshuClient>()

        // Start listening BEFORE the action.
        val notificationDeferred = async {
            client.notificationService.notifications.flow()
                .filter { res ->
                    res.getOrNull()?.toList()?.any { item ->
                        val typeId = if (item is Entity<*>) item.type.get() else item.id
                        typeId == FileAddedNotification.ID &&
                                item.getField<TString>(FileAddedNotification.Fields.PATH)
                                    .get().contains("report.pdf")
                    } == true
                }
                .first()
        }

        // Trigger action — uploading a file must surface in the recent-events feed.
        client.fileService.defaultFileSystem
            .uploadFile("/", "report.pdf", byteArrayOf(1, 2, 3))
            .onFailure { error(it) }

        // Wait for result
        notificationDeferred.await()
    }
}
