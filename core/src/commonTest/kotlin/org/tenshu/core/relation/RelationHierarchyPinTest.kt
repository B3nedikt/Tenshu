package org.tenshu.core.relation

import org.tenshu.core.schema.dynamicPropertyRef
import org.tenshu.core.schema.propertyRef
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Pins the unified relation hierarchy
 * (`docs/adr/0013-property-authoring-unification.md`): the property
 * selectors are now [Relation]s, mirroring the schema-descriptor side where
 * `PropertyRelationSchema` is already a `RelationSchema`. `Relation` itself is a
 * fieldless marker — `from`/`to` live on [ServiceDataRelation] (covered at
 * runtime by the file-/notification-service resolution suites), not here.
 */
class RelationHierarchyPinTest {

    @Test
    fun staticPropertyIsARelation() {
        assertIs<Relation>(StaticPropertyRelation.create(reference = propertyRef("test/relation/static")))
    }

    @Test
    fun dynamicPropertyIsARelation() {
        assertIs<Relation>(DynamicPropertyRelation.create(reference = dynamicPropertyRef("test/relation/dynamic")))
    }
}
