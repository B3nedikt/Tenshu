package org.tenshu.core.common.proxy.executor

import org.tenshu.core.Contains
import org.tenshu.core.EqualTo
import org.tenshu.core.OneOf
import org.tenshu.core.Get
import org.tenshu.core.common.proxy.ResolveImplementation
import org.tenshu.core.Resolve
import org.tenshu.core.IsA
import org.tenshu.core.Lowercase
import org.tenshu.core.SubstringAfterLast
import org.tenshu.core.IfEmptyError
import org.tenshu.core.collection.list.Count
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.common.proxy.ListCountImplementation
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.EntitySelect
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.MaybeSelect
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.app.RegisterApp
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.app.proxy.AnnounceSessionImplementation
import org.tenshu.core.app.RemoveApp
import org.tenshu.core.app.SetAppGrant
import org.tenshu.core.common.app.proxy.RegisterAppImplementation
import org.tenshu.core.common.app.proxy.RemoveAppImplementation
import org.tenshu.core.common.app.proxy.SetAppGrantImplementation
import org.tenshu.core.InRange
import org.tenshu.core.common.impl.ContainsImplementation
import org.tenshu.core.common.impl.InRangeImplementation
import org.tenshu.core.common.impl.EqualToImplementation
import org.tenshu.core.common.impl.OneOfImplementation
import org.tenshu.core.common.impl.IsAImplementation
import org.tenshu.core.common.impl.LowercaseImplementation
import org.tenshu.core.common.impl.SubstringAfterLastImplementation
import org.tenshu.core.common.proxy.FilterImplementation
import org.tenshu.core.common.proxy.GetAllImplementation
import org.tenshu.core.common.proxy.IfEmptyErrorImplementation
import org.tenshu.core.common.proxy.ListDropImplementation
import org.tenshu.core.common.proxy.ListStreamEndImplementation
import org.tenshu.core.common.proxy.ListStreamImplementation
import org.tenshu.core.common.proxy.ListTakeImplementation
import org.tenshu.core.common.proxy.MaybeGetImplementation
import org.tenshu.core.common.proxy.SearchAllImplementation
import org.tenshu.core.common.proxy.SearchImplementation
import org.tenshu.core.common.proxy.SelectImplementation
import org.tenshu.core.common.proxy.SortByImplementation
import org.tenshu.core.common.proxy.TraverseImplementation
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.ResolveEntityDataId

fun implementationFactory(
    id: String,
): OperationImplementation? {
    return when (id) {
        Search.ID -> SearchImplementation()
        SearchAll.ID -> SearchAllImplementation()
        Traverse.ID -> TraverseImplementation()
        Filter.ID -> FilterImplementation()
        Get.ID -> MaybeGetImplementation()
        Resolve.ID -> ResolveImplementation()
        GetAll.ID -> GetAllImplementation()
        IfEmptyError.ID -> IfEmptyErrorImplementation()
        Stream.ID -> ListStreamImplementation()
        EndStream.ID -> ListStreamEndImplementation()
        Select.ID -> SelectImplementation()
        // Maybe / Entity siblings: same engine impl, different chain-DSL
        // entry-points. SelectImplementation.execute dispatches on
        // `caller: Maybe<*>` and `caller: Entity<*>` for the respective
        // paths; routing all three IDs here shares one implementation
        // instance for the three shapes.
        MaybeSelect.ID -> SelectImplementation()
        EntitySelect.ID -> SelectImplementation()
        Take.ID -> ListTakeImplementation()
        Drop.ID -> ListDropImplementation()
        Count.ID -> ListCountImplementation()
        SortBy.ID -> SortByImplementation()
        EqualTo.ID -> EqualToImplementation()
        OneOf.ID -> OneOfImplementation()
        Contains.ID -> ContainsImplementation()
        InRange.ID -> InRangeImplementation()
        SubstringAfterLast.ID -> SubstringAfterLastImplementation()
        Lowercase.ID -> LowercaseImplementation()
        IsA.ID -> IsAImplementation()
        GetStringDataId.ID -> GetStringDataIdImplementation()
        ResolveEntityDataId.ID -> ResolveEntityDataIdImplementation()
        RegisterApp.ID -> RegisterAppImplementation()
        RemoveApp.ID -> RemoveAppImplementation()
        SetAppGrant.ID -> SetAppGrantImplementation()
        AnnounceSession.ID -> AnnounceSessionImplementation()
        else -> null
    }
}
