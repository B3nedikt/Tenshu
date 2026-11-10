package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins every `cfapi.h` struct [CloudFilesLayouts] spells to the x64 C layout (sizes and offsets computed
 * from the documented declarations by the natural-alignment rules MSVC uses). Runs on every host: it
 * exercises FFM's layout arithmetic only, never `cldapi.dll`.
 *
 * ⚠ This pins our reading of the declarations, not the header itself (no Windows SDK headers on the
 * build machines). The live check is the X0 spike: the OS reports `CF_CALLBACK_INFO.StructSize` and the
 * callback `ParamSize`, and the spike fails if they differ from these.
 */
class CloudFilesLayoutsTest {

    private val l = CloudFilesLayouts

    private fun assertLayout(layout: StructLayout, size: Long, vararg offsets: Pair<String, Long>) {
        assertEquals(size, layout.byteSize(), "sizeof ${layout.name().orElse("struct")}")
        for ((field, offset) in offsets) assertEquals(offset, l.off(layout, field), "offset of $field")
    }

    @Test
    fun callbackRegistrationAndGuid() {
        assertLayout(l.GUID, 16, "Data1" to 0, "Data2" to 4, "Data3" to 6, "Data4" to 8)
        assertEquals(4, l.GUID.byteAlignment(), "GUID is 4-aligned, which is what puts ProviderId at 52")
        assertLayout(l.CALLBACK_REGISTRATION, 16, "Type" to 0, "Callback" to 8)
    }

    @Test
    fun syncRegistrationAndPolicies() {
        assertLayout(
            l.SYNC_REGISTRATION, 72,
            "StructSize" to 0, "ProviderName" to 8, "ProviderVersion" to 16, "SyncRootIdentity" to 24,
            "SyncRootIdentityLength" to 32, "FileIdentity" to 40, "FileIdentityLength" to 48, "ProviderId" to 52,
        )
        assertLayout(
            l.SYNC_POLICIES, 24,
            "StructSize" to 0, "HydrationPrimary" to 4, "HydrationModifier" to 6, "PopulationPrimary" to 8,
            "PopulationModifier" to 10, "InSync" to 12, "HardLink" to 16, "PlaceholderManagement" to 20,
        )
    }

    @Test
    fun placeholderCreateInfo() {
        assertLayout(
            l.FILE_BASIC_INFO, 40,
            "CreationTime" to 0, "LastAccessTime" to 8, "LastWriteTime" to 16, "ChangeTime" to 24, "FileAttributes" to 32,
        )
        assertLayout(l.FS_METADATA, 48, "BasicInfo" to 0, "FileSize" to 40)
        assertLayout(
            l.PLACEHOLDER_CREATE_INFO, 88,
            "RelativeFileName" to 0, "FsMetadata" to 8, "FileIdentity" to 56, "FileIdentityLength" to 64,
            "Flags" to 68, "Result" to 72, "CreateUsn" to 80,
        )
    }

    @Test
    fun callbackInfoAndParameters() {
        assertLayout(
            l.CALLBACK_INFO, 152,
            "StructSize" to 0, "ConnectionKey" to 8, "CallbackContext" to 16, "VolumeGuidName" to 24,
            "VolumeDosName" to 32, "VolumeSerialNumber" to 40, "SyncRootFileId" to 48, "SyncRootIdentity" to 56,
            "SyncRootIdentityLength" to 64, "FileId" to 72, "FileSize" to 80, "FileIdentity" to 88,
            "FileIdentityLength" to 96, "NormalizedPath" to 104, "TransferKey" to 112, "PriorityHint" to 120,
            "CorrelationVector" to 128, "ProcessInfo" to 136, "RequestKey" to 144,
        )
        assertLayout(
            l.PROCESS_INFO, 48,
            "StructSize" to 0, "ProcessId" to 4, "ImagePath" to 8, "PackageName" to 16, "ApplicationId" to 24,
            "CommandLine" to 32, "SessionId" to 40,
        )
        assertLayout(
            l.CALLBACK_PARAMETERS_FETCH_DATA, 64,
            "ParamSize" to 0, "Flags" to 8, "RequiredFileOffset" to 16, "RequiredLength" to 24,
            "OptionalFileOffset" to 32, "OptionalLength" to 40, "LastDehydrationTime" to 48, "LastDehydrationReason" to 56,
        )
        assertLayout(l.CALLBACK_PARAMETERS_CANCEL, 32, "Flags" to 8, "FileOffset" to 16, "Length" to 24)
    }

    @Test
    fun operationInfoAndTransferData() {
        assertLayout(
            l.OPERATION_INFO, 48,
            "StructSize" to 0, "Type" to 4, "ConnectionKey" to 8, "TransferKey" to 16, "CorrelationVector" to 24,
            "SyncStatus" to 32, "RequestKey" to 40,
        )
        // = CF_SIZE_OF_OP_PARAM(TransferData), the ParamSize CfExecute expects.
        assertLayout(
            l.OPERATION_PARAMETERS_TRANSFER_DATA, 40,
            "ParamSize" to 0, "Flags" to 8, "CompletionStatus" to 12, "Buffer" to 16, "Offset" to 24, "Length" to 32,
        )
    }

    @Test
    fun aGuidIsWrittenInWindowsMemoryOrder() {
        Arena.ofConfined().use { arena ->
            val guid = arena.allocate(l.GUID)
            l.writeGuid(guid, UUID.fromString("00000036-0000-0000-c000-000000000046")) // IID_IAsyncInfo
            assertContentEquals(
                byteArrayOf(0x36, 0, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 0, 0, 0, 0, 0, 0, 0x46),
                guid.toArray(ValueLayout.JAVA_BYTE),
            )
            l.writeGuid(guid, UUID.fromString("7c1305c4-99f9-41ac-8904-ab055d654926"))
            assertContentEquals(
                byteArrayOf(
                    0xC4.toByte(), 0x05, 0x13, 0x7C, 0xF9.toByte(), 0x99.toByte(), 0xAC.toByte(), 0x41,
                    0x89.toByte(), 0x04, 0xAB.toByte(), 0x05, 0x5D, 0x65, 0x49, 0x26,
                ),
                guid.toArray(ValueLayout.JAVA_BYTE),
            )
        }
    }
}
