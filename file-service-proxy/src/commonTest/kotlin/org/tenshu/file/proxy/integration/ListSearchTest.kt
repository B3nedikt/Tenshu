package org.tenshu.file.proxy.integration


import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ListSearchTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = createFileProxy()
    }

    @Test
    fun fileInTopLevelGetsFound() {

        val lastModified = Clock.System.now().toEpochMilliseconds()
        val rootFolderContent = TListDto(
            listOf(
                File.create(
                    context = testProxy.tContext,
                    path = "/test.txt",
                ),
                File.create(
                    context = testProxy.tContext,
                    path = "/test2.txt",
                ),
                Folder.create(
                    context = testProxy.tContext,
                    path = "/targetFolder",
                    files = emptyList()
                )
            )
        )

        val result = testProxy.executionContext.execute<Maybe<Folder>>(
            operationId = Search.ID,
            caller = rootFolderContent,
            params = mapOf(
                Search.Params.PREDICATE to listOf(
                    testProxy.executionContext.context.schemaProvider()
                        .getReference(File.Field.PATH),
                    OperationDto(
                        params = mapOf(EqualTo.Params.OTHER to "/targetFolder".toTString(testProxy.tContext)),
                        schema = EqualTo.Schema
                    )
                ).toTList(testProxy.tContext)
            )
        )

        assertTrue(result is Maybe.Some<*>)
        assertEquals(
            expected = Folder.create(
                context = testProxy.tContext,
                path = "/targetFolder",
                files = emptyList()
            ),
            actual = result.value
        )
    }

    @Test
    fun fileOnSecondLevelGetsFound() {

        val lastModified = Clock.System.now().toEpochMilliseconds()
        val rootFolderContent = TListDto(
            listOf(
                File.create(
                    context = testProxy.tContext,
                    path = "/test.txt",
                ),
                File.create(
                    context = testProxy.tContext,
                    path = "/test2.txt",
                ),
                Folder.create(
                    context = testProxy.tContext,
                    path = "/targetFolder",
                    files = listOf(
                        File.create(
                            context = testProxy.tContext,
                            path = "/targetFile.txt",
                        )
                    )
                )
            )
        )

        val result = testProxy.executionContext.execute<Maybe<File>>(
            operationId = Search.ID,
            caller = rootFolderContent,
            params = mapOf(
                Search.Params.PREDICATE to listOf(
                    testProxy.executionContext.context.schemaProvider()
                        .getReference(File.Field.PATH),
                    OperationDto(
                        params = mapOf(EqualTo.Params.OTHER to "/targetFile.txt".toTString(testProxy.tContext)),
                        schema = EqualTo.Schema
                    )
                ).toTList(testProxy.tContext)
            )
        )

        assertTrue(result is Maybe.Some<*>)
        assertEquals(
            expected = File.create(
                context = testProxy.tContext,
                path = "/targetFile.txt",
            ),
            actual = result.value
        )
    }

    @Test
    fun searchResultIsEmptyWhenNoMatchIsFound() {
        val lastModified = Clock.System.now().toEpochMilliseconds()
        val rootFolderContent = TListDto(
            listOf(
                File.create(
                    context = testProxy.tContext,
                    path = "/test.txt",
                )
            )
        )

        val result = testProxy.executionContext.execute<Maybe<File>>(
            operationId = Search.ID,
            caller = rootFolderContent,
            params = mapOf(
                Search.Params.PREDICATE to listOf(
                    testProxy.executionContext.context.schemaProvider()
                        .getReference(File.Field.PATH),
                    OperationDto(
                        params = mapOf(
                            EqualTo.Params.OTHER to "/non-existent.txt".toTString(
                                testProxy.tContext
                            )
                        ),
                        schema = EqualTo.Schema
                    )
                ).toTList(testProxy.tContext)
            )
        )

        assertTrue(result is Maybe.Empty<*>, "Result should be Empty when no match is found")
    }
}
