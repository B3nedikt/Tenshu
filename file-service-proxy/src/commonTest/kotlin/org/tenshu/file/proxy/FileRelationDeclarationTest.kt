package org.tenshu.file.proxy

import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.relation.RelationScope
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.preference.PreferenceService
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the file service's relation *declarations* mean, now that R2 derives
 * maintenance from them (`core-relation-system.md` §9.2.1 / §10.2).
 *
 * These are cheap assertions guarding expensive mistakes: a missing `maintained = true` is a
 * relation that silently stops being maintained, and a missing
 * `chainReadable = false` is a public read surface a relation deliberately does
 * not have. Neither shows up as a compile error, and both would otherwise only
 * surface as "the list is empty" much later.
 */
class FileRelationDeclarationTest {

    private fun registry(): SchemaRegistry =
        createFileProxy().executionContext.schemaProvider as SchemaRegistry

    @Test
    fun placementsIsMaintainedButNotChainReadable() {
        val registry = registry()

        val spec = registry.relationSpecs().single {
            (it.scope as? RelationScope.Service)?.relationId == FileService.Relations.PLACEMENTS
        }
        assertNotNull(spec, "PLACEMENTS declares `maintained = true`, so a spec must exist")

        // The dedicated `listPlacements` op is the read surface (§9.7), so the
        // relation must register NO chain reference — the same guarantee
        // `RelationMaintainerTest` pins for the four op-only identity relations.
        assertNull(
            registry.getServiceDataRelation(
                ServiceTypeReferenceDto(FileService.ID),
                EntityListReferenceDto(FileService.Relations.PLACEMENTS),
            ),
            "PLACEMENTS declares chainReadable = false, so it must register no " +
                "ServiceDataRelation; adding one would give it a public chain read " +
                "surface it deliberately does not have",
        )
    }

    @Test
    fun modificationsAndSharesStayChainReadable() {
        val registry = registry()
        for (relationId in listOf(FileService.Relations.MODIFICATIONS, FileService.Relations.SHARES)) {
            assertNotNull(
                registry.getServiceDataRelation(
                    ServiceTypeReferenceDto(FileService.ID),
                    EntityListReferenceDto(relationId),
                ),
                "$relationId is read through the chain (`fileService.modifications` / " +
                    "`.shares`), so the schema walk must still register its reference",
            )
        }
    }

    @Test
    fun entityScopedRelationsAreDeclared() {
        val specs = registry().relationSpecs()

        // Folder.FILES — the one relation whose raw array order the user could
        // always see (Drive's default listing is unsorted). R2b made every
        // relation converge the way this one already did (§10.4).
        val folderFiles = specs.single {
            (it.scope as? RelationScope.EntityField)?.fieldRef == Folder.Fields.FILES
        }
        assertEquals(File.ID, folderFiles.elementTypeId)

        val history = specs.single {
            (it.scope as? RelationScope.EntityProperty)?.propertyId == File.FileHistory.id
        }
        assertEquals(FileModification.ID, history.elementTypeId)

        val sharedChildren = specs.single {
            (it.scope as? RelationScope.EntityProperty)?.propertyId ==
                SharedFile.CHILDREN_RELATION_ID
        }
        assertEquals(SharedFile.ID, sharedChildren.elementTypeId)
    }

    @Test
    fun fileSyncSettingsKeepsItsHandWrittenCrossServiceRegistration() {
        // The projection relation hangs off PreferenceService but is contributed by
        // :file-service-common, so no schema walk can reach it — the second
        // permanent carve-out from §9.2.1 (§10.2). It must therefore have a manual
        // ServiceDataRelation and NO spec.
        val registry = registry()
        assertNotNull(
            registry.getServiceDataRelation(
                ServiceTypeReferenceDto(PreferenceService.ID),
                EntityListReferenceDto(FileSyncSetting.RELATION),
            ),
            "FileSyncSetting.RELATION is cross-service; its hand-written " +
                "serviceDataRelation registration is not redundant and must stay",
        )
        assertTrue(
            registry.relationSpecs().none {
                (it.scope as? RelationScope.Service)?.relationId == FileSyncSetting.RELATION
            },
            "the fileSyncSettings projection is a 2→1 fold, not membership — it must " +
                "not gain a generic maintenance entry",
        )
    }
}
