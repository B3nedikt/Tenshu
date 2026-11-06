package org.tenshu.core.common.relation

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.serviceDataRelation as registerServiceDataRelation
import org.tenshu.core.common.module.service as registerService
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.core.TContext
import org.tenshu.core.core.createDto
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.typeOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the generic [RelationMaintainer] and the schema-driven declaration path
 * that feeds it (`core-relation-system.md` §9.2 / §9.2.1), on the axes no
 * migrated module exercises yet:
 *
 *  - **subtype matching** — the deliberate improvement over the hand-written
 *    copies that compare `type.get() != X.ID`;
 *  - **probe-first convergence** (R2b): a re-saved member keeps its slot, and an
 *    array polluted by the two-writer era still converges to exactly one entry;
 *  - `onDataDeleted` symmetry;
 *  - both entity-scoped [RelationScope] variants;
 *  - the `service(...)` walk: `chainReadable`, opt-in maintenance, and
 *    coexistence with a hand-written registration (the shape every module used
 *    before R2, and cross-service relations still do).
 *
 * The write counts and the listener contract that go with probe-first membership
 * are pinned separately, in [RelationMaintainerWriteCountTest].
 */
class RelationMaintainerTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createRelationTestRegistry())
        testProxy.executionContext.installRelationMaintainer()
    }

    // --- subtype matching ------------------------------------------------

    @Test
    fun subtypeOfTheDeclaredElementType_isMaintained() {
        // A bare `type == elementTypeId` comparison — what several of the
        // hand-written maintainers do — would drop this row on the floor, even
        // though the store keys it under the SAME identity root.
        testProxy.executionContext.saveData(child("sub-1", 1))

        assertEquals(listOf("sub-1"), namesIn(flatRef()))
    }

    @Test
    fun unrelatedType_isIgnored() {
        testProxy.executionContext.saveData(unrelated("other"))

        assertEquals(emptyList(), namesIn(flatRef()))
    }

    // --- membership convergence (R2b) -------------------------------------

    @Test
    fun aReSavedMemberKeepsItsSlot() {
        testProxy.executionContext.saveData(item("a", 1))
        testProxy.executionContext.saveData(item("b", 2))
        testProxy.executionContext.saveData(item("a", 3))

        // ⚠ THE R2b BEHAVIOUR CHANGE, in one assertion. Until R2b this relation
        // was `MembershipOrder.MostRecentLast` and the same input produced
        // ["b", "a"] — the re-saved member jumped to the end of the array, which
        // eleven consumers render straight to the user (§10.3).
        assertEquals(listOf("a", "b"), namesIn(flatRef()))
        assertEquals(listOf("a", "b"), namesIn(secondRef()), "…for every relation, not a chosen few")
    }

    @Test
    fun convergesToOneEntryEvenFromAPollutedArray() {
        repeat(2) { testProxy.executionContext.database.addToArray(flatRef(), item("a", 1)) }
        assertEquals(listOf("a", "a"), namesIn(flatRef()), "array should be polluted by setup")

        testProxy.executionContext.saveData(item("a", 2))

        assertEquals(listOf("a"), namesIn(flatRef()), "convergence to exactly-one is preserved")
    }

    // --- deletion ---------------------------------------------------------

    @Test
    fun onDataDeleted_removesTheMemberFromEveryDeclaredRelation() {
        testProxy.executionContext.saveData(item("a", 1))
        testProxy.executionContext.saveData(item("b", 2))
        assertEquals(listOf("a", "b"), namesIn(flatRef()))
        assertEquals(listOf("a", "b"), namesIn(secondRef()))
        assertEquals(listOf("a", "b"), namesIn(ref(OP_ONLY_RELATION)))

        testProxy.executionContext.deleteData(idOf("a"))

        assertEquals(listOf("b"), namesIn(flatRef()))
        assertEquals(listOf("b"), namesIn(secondRef()))
        assertEquals(listOf("b"), namesIn(ref(OP_ONLY_RELATION)))
    }

    // --- the `service(...)` walk -----------------------------------------

    @Test
    fun chainReadableFalse_registersNoChainReferenceButIsStillMaintained() {
        testProxy.executionContext.saveData(item("a", 1))

        assertEquals(
            listOf("a"),
            namesIn(ref(OP_ONLY_RELATION)),
            "an op-read relation is still derived — only its READ surface differs",
        )
        assertNull(
            chainRelation(OP_ONLY_RELATION),
            "chainReadable = false must register NO ServiceDataRelation; a dedicated op " +
                "is the read surface (IdentityService.Relations' four op-read arrays)",
        )
        assertNotNull(
            chainRelation(FLAT_RELATION),
            "the default (chainReadable = true) still registers the chain reference",
        )
    }

    @Test
    fun aRelationThatDoesNotDeclareItselfMaintained_isNotMaintained() {
        // Opt-in maintenance. R1 needed it for coexistence with the then-unmigrated
        // modules; after R2 it is what keeps a declared-but-not-membership relation
        // (`HealthService.LATEST_SAMPLES`, a rollup) out of the mechanism.
        testProxy.executionContext.saveData(item("a", 1))

        assertEquals(emptyList(), namesIn(ref(UNMAINTAINED_RELATION)))
        assertNotNull(
            chainRelation(UNMAINTAINED_RELATION),
            "…while its chain reference is still registered, exactly as before",
        )
    }

    @Test
    fun handWrittenRegistrationCoexistsWithTheWalk_andWins() {
        // `service(...)` registers first, then the module's own
        // `serviceDataRelation(fromId, targetId, …)` overwrites with an identical
        // value — a map assignment, so exactly one entry either way. This is what
        // lets both mechanisms live side by side through R2.
        val registry = createRelationTestRegistry(withManualRegistration = true)
        val relation = registry.getServiceDataRelation(
            ServiceTypeReferenceDto(RelationTestService.ID),
            EntityListReferenceDto(FLAT_RELATION),
        )
        assertNotNull(relation)
        assertEquals(RelationTestService.ID, (relation.from as Reference).get())
        assertEquals(FLAT_RELATION, (relation.to as Reference).get())
        assertEquals(
            1,
            RelationRegistry.from(registry).specs.count {
                it.scope == RelationScope.Service(FLAT_RELATION)
            },
            "a relation is maintained once, not once per registration path",
        )
    }

    @Test
    fun registryIndexesTheDeclaredSpecs() {
        val registry = RelationRegistry.from(testProxy.schemaRegistry)
        // ⚠ Scoped to this test service's own relations. R2 migrated `AppService`
        // (declared in `coreCommonModule`, which the test registry includes), so
        // `registry.specs` is no longer only what this file declares — the number
        // now moves whenever another core relation joins the mechanism, which is
        // not what this assertion is about.
        assertEquals(
            3,
            registry.specs.count { (it.scope as? RelationScope.Service)?.relationId in TEST_RELATIONS },
            "FLAT + SECOND + OP_ONLY; the one that is not `maintained` is absent",
        )
        assertEquals(3, registry.declaredFor(SyntheticTestData.ID).size)
        assertTrue(registry.declaredFor("nothing/declared").isEmpty())
        assertTrue(
            registry.specs.none { it.isEntity },
            "isEntity is DERIVED from the element schema, the same way the chain reader derives it",
        )
    }

    @Test
    fun installIsIdempotent_soOneObserverServesEveryMigratedService() {
        // Every migrated `init<X>Service()` calls it; a second call must not add
        // a second observer (which would double every add/remove).
        testProxy.executionContext.installRelationMaintainer()
        testProxy.executionContext.installRelationMaintainer()

        testProxy.executionContext.saveData(item("a", 1))

        assertEquals(listOf("a"), namesIn(flatRef()))
    }

    // --- entity-scoped relations -----------------------------------------
    //
    // These are not service relations, so `ServiceSchema.relationSchemas` cannot
    // carry them (§9.2.1); R2 gave them their own declaration verb,
    // `ModuleConfiguration.relationMembership(spec)` — see `Folder.FILES` /
    // `File.FileHistory` in `fileServiceCommonModule`. Driven here from a
    // hand-built registry so the scopes are pinned independently of that module.

    @Test
    fun entityFieldScope_routesMembersToTheirParentsArray() {
        val proxy = entityScopedProxy()

        proxy.executionContext.saveData(itemIn(proxy, "p/one", 1))
        proxy.executionContext.saveData(itemIn(proxy, "p/two", 2))
        proxy.executionContext.saveData(itemIn(proxy, "q/three", 3))
        // No parent → `parentOf` returns null → the relation skips it.
        proxy.executionContext.saveData(itemIn(proxy, "rootless", 4))

        assertEquals(listOf("p/one", "p/two"), namesIn(fieldRef(proxy, "p"), proxy))
        assertEquals(listOf("q/three"), namesIn(fieldRef(proxy, "q"), proxy))
    }

    @Test
    fun entityPropertyScope_routesMembersToTheirParentsArray() {
        val proxy = entityScopedProxy()

        proxy.executionContext.saveData(itemIn(proxy, "p/one", 1))
        proxy.executionContext.saveData(itemIn(proxy, "q/two", 2))

        assertEquals(listOf("p/one"), namesIn(propertyRef(proxy, "p"), proxy))
        assertEquals(listOf("q/two"), namesIn(propertyRef(proxy, "q"), proxy))
    }

    @Test
    fun entityScopes_removeOnDelete() {
        val proxy = entityScopedProxy()
        proxy.executionContext.saveData(itemIn(proxy, "p/one", 1))
        assertEquals(listOf("p/one"), namesIn(fieldRef(proxy, "p"), proxy))
        assertEquals(listOf("p/one"), namesIn(propertyRef(proxy, "p"), proxy))

        // The delete path feeds `parentOf` the element's own DataId, not a row —
        // the §9.2 gap §10.1 records.
        proxy.executionContext.deleteData(
            DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "p/one")
        )

        assertEquals(emptyList(), namesIn(fieldRef(proxy, "p"), proxy))
        assertEquals(emptyList(), namesIn(propertyRef(proxy, "p"), proxy))
    }

    // --- fixtures ---------------------------------------------------------

    private fun entityScopedProxy(): TestProxy {
        val proxy = TestProxy(schemaRegistry = createRelationTestRegistry())
        proxy.executionContext.addDataObserver(
            RelationMaintainer(
                proxy.executionContext,
                RelationRegistry(
                    listOf(
                        RelationSpec(
                            elementTypeId = SyntheticTestData.ID,
                            scope = RelationScope.EntityField(CHILDREN_FIELD, parentOfName),
                        ),
                        RelationSpec(
                            elementTypeId = SyntheticTestData.ID,
                            scope = RelationScope.EntityProperty(CHILDREN_PROPERTY, parentOfName),
                        ),
                    )
                )
            )
        )
        return proxy
    }

    private fun item(name: String, value: Long): SyntheticTestData = itemIn(testProxy, name, value)

    private fun itemIn(proxy: TestProxy, name: String, value: Long): SyntheticTestData =
        SyntheticTestData.Factory.create(proxy.tContext, name, value)

    private fun child(name: String, value: Long): Data =
        testProxy.tContext.createDto(ChildData.ID, listOf(name, value))

    private fun unrelated(name: String): Data =
        testProxy.tContext.createDto(UnrelatedData.ID, listOf(name))

    private fun idOf(name: String): DataId =
        DataId.StringDataId.create(testProxy.tContext, SyntheticTestData.ID, name)

    private fun chainRelation(relationId: String) = testProxy.schemaRegistry.getServiceDataRelation(
        ServiceTypeReferenceDto(RelationTestService.ID),
        EntityListReferenceDto(relationId),
    )

    private fun flatRef(): ArrayDatabaseReference = ref(FLAT_RELATION)

    private fun secondRef(): ArrayDatabaseReference = ref(SECOND_RELATION)

    private fun ref(relationId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID,
    )

    private fun fieldRef(proxy: TestProxy, parent: String) = ArrayDatabaseReference(
        arrayId = DataId.EntityFieldDataId.create(
            context = proxy.tContext,
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(
                proxy.tContext, SyntheticTestData.ID, parent
            ) as DataId.EntityDataId,
            fieldRef = CHILDREN_FIELD,
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID,
    )

    private fun propertyRef(proxy: TestProxy, parent: String) = ArrayDatabaseReference(
        arrayId = DataId.EntityPropertyDataId.create(
            context = proxy.tContext,
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(
                proxy.tContext, SyntheticTestData.ID, parent
            ) as DataId.EntityDataId,
            propertyId = CHILDREN_PROPERTY,
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID,
    )

    private fun namesIn(
        reference: ArrayDatabaseReference,
        proxy: TestProxy = testProxy,
    ): List<String> =
        proxy.executionContext.database.getArrayData(reference)
            ?.map { nameOf(it) }
            ?.toList()
            ?: emptyList()

    private fun nameOf(data: Data): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
        is Composite -> data.getField<TString>(SyntheticTestData.Companion.Field.NAME).get()
        else -> error("unexpected member shape: $data")
    }
}

private const val FLAT_RELATION = "${RelationTestService.ID}/flat"
private const val SECOND_RELATION = "${RelationTestService.ID}/second"
private const val OP_ONLY_RELATION = "${RelationTestService.ID}/opOnly"
private const val UNMAINTAINED_RELATION = "${RelationTestService.ID}/unmaintained"
private val TEST_RELATIONS = setOf(FLAT_RELATION, SECOND_RELATION, OP_ONLY_RELATION, UNMAINTAINED_RELATION)
private const val CHILDREN_FIELD = "test/relation/childrenField"
private const val CHILDREN_PROPERTY = "test/relation/childrenProperty"

/**
 * A synthetic service declaring four relations over the same element type, one
 * per case the walk has to distinguish: maintained + chain-readable, a second
 * maintained relation over the same type, maintained but op-read only, and
 * declared-but-not-maintained.
 */
private object RelationTestService {
    const val ID = "test/relation/service"

    val Schema: ServiceSchema = service(
        id = ID,
        relations = listOf(
            serviceDataRelation(
                id = FLAT_RELATION,
                type = TList.typeOf(SyntheticTestData.Schema),
                maintained = true,
            ),
            serviceDataRelation(
                id = SECOND_RELATION,
                type = TList.typeOf(SyntheticTestData.Schema),
                maintained = true,
            ),
            serviceDataRelation(
                id = OP_ONLY_RELATION,
                type = TList.typeOf(SyntheticTestData.Schema),
                maintained = true,
                chainReadable = false,
            ),
            serviceDataRelation(
                id = UNMAINTAINED_RELATION,
                type = TList.typeOf(SyntheticTestData.Schema),
            ),
        )
    )
}

/**
 * A subtype of [SyntheticTestData] with no fields of its own — it inherits the
 * parent's fields *and* its `DataIdProvider` trait, so `findIdentityRoot` keys
 * its rows under `SyntheticTestData.ID`. Exactly the `Folder`-under-`File` shape
 * the `isSubtypeOf` match exists for.
 */
private interface ChildData : SyntheticTestData {
    companion object {
        const val ID = "test/relation/child"
        val Schema: CompositeSchema =
            org.tenshu.core.schema.composite(id = ID, parent = SyntheticTestData.Schema)
    }

    override val schema: CompositeSchema get() = Schema

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), ChildData {
        override val schema: CompositeSchema get() = Schema
    }
}

/** A registered composite that is *not* a [SyntheticTestData] — the negative case. */
private interface UnrelatedData : Composite {
    companion object {
        const val ID = "test/relation/unrelated"
        const val NAME = "$ID/name"
        val DataIdTraitSchema = stringDataId("$ID/dataIdProvider", NAME)
        val Schema: CompositeSchema = org.tenshu.core.schema.composite(
            id = ID,
            traits = listOf(DataIdTraitSchema),
            fields = { string(NAME) },
        )
    }

    override val schema: CompositeSchema get() = Schema

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), UnrelatedData {
        override val schema: CompositeSchema get() = Schema
    }
}

/**
 * Derives an element's parent from its name (`"p/one"` → `"p"`), mirroring
 * `ParentFilesRelationMaintainer`'s path split. Handles both shapes the lambda
 * is fed: the saved row, and — on the delete path — the element's own `DataId`.
 */
private val parentOfName: (ExecutionContext, Data) -> DataId.EntityDataId? = { ctx, element ->
    val name = when (element) {
        is DataId.StringDataId -> element.keyValue.get()
        is Entity<*> -> element.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME).get()
        is Composite -> element.getField<TString>(SyntheticTestData.Companion.Field.NAME).get()
        else -> null
    }
    val parent = name?.substringBeforeLast('/', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
    parent?.let {
        DataId.StringDataId.create(
            context = ctx.context,
            type = SyntheticTestData.ID,
            keyValue = it,
        ) as DataId.EntityDataId
    }
}

private fun createRelationTestRegistry(
    withManualRegistration: Boolean = false,
): SchemaRegistry {
    val registry = createSyntheticTestRegistry()
    registry.registerConfig(
        tModule {
            composite(
                interfaceClass = ChildData::class,
                dataSchema = ChildData.Schema,
                dtoFactory = ChildData::Dto,
            )
            composite(
                interfaceClass = UnrelatedData::class,
                dataSchema = UnrelatedData.Schema,
                dtoFactory = UnrelatedData::Dto,
            )
            trait(
                dataSchema = UnrelatedData.Schema,
                traitSchema = UnrelatedData.DataIdTraitSchema,
                traitFactory = { context, schema ->
                    StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
                },
            )

            registerService(RelationTestService.Schema)

            if (withManualRegistration) {
                // The shape all twelve unmigrated modules still use.
                registerServiceDataRelation(
                    fromId = RelationTestService.ID,
                    targetId = FLAT_RELATION,
                    relation = org.tenshu.core.relation.ServiceDataRelation.create(
                        from = RelationTestService.ID,
                        to = FLAT_RELATION,
                        type = TList.typeOf(SyntheticTestData.Schema),
                    ),
                )
            }
        }
    )
    return registry
}
