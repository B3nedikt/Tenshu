package org.tenshu.core.common.module

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Composite
import org.tenshu.core.Contains
import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.OneOf
import org.tenshu.core.SubstringAfterLast
import org.tenshu.core.GenericError
import org.tenshu.core.Get
import org.tenshu.core.IfEmptyError
import org.tenshu.core.InternalError
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.InvalidParameter
import org.tenshu.core.IsA
import org.tenshu.core.Lowercase
import org.tenshu.core.Maybe
import org.tenshu.core.MissingParameter
import org.tenshu.core.NotFoundError
import org.tenshu.core.Operation
import org.tenshu.core.StreamElement
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TConstant
import org.tenshu.core.Resolve
import org.tenshu.core.common.impl.ResolveRo
import org.tenshu.core.common.impl.TConstantRo
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TValue
import org.tenshu.core.UnannouncedSessionError
import org.tenshu.core.UnsupportedOperationError
import org.tenshu.core.UnknownParameter
import org.tenshu.core.WrongParameter
import org.tenshu.core.collection.TMap
import org.tenshu.core.collection.TPair
import org.tenshu.core.collection.list.Count
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.common.impl.array.ListCountRo
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.Find
import org.tenshu.core.collection.list.EntitySelect
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.MaybeSelect
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.common.impl.CompositeEntityDataIdDto
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.impl.EntityFieldDataIdDto
import org.tenshu.core.common.impl.EntityPropertyDataIdDto
import org.tenshu.core.InRange
import org.tenshu.core.common.impl.ContainsRo
import org.tenshu.core.common.impl.InRangeRo
import org.tenshu.core.common.impl.EqualToRo
import org.tenshu.core.common.impl.OneOfRo
import org.tenshu.core.common.impl.IsARo
import org.tenshu.core.common.impl.LowercaseRo
import org.tenshu.core.common.impl.SubstringAfterLastRo
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.UnannouncedSessionErrorDto
import org.tenshu.core.common.impl.UnsupportedOperationErrorDto
import org.tenshu.core.common.impl.GetStringDataIdDto
import org.tenshu.core.common.impl.HybridLogicalClockDto
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.common.impl.InvalidCallerErrorDto
import org.tenshu.core.common.impl.InvalidParameterDto
import org.tenshu.core.common.impl.LongDataIdDto
import org.tenshu.core.common.impl.MaybeEmptyDto
import org.tenshu.core.common.impl.MaybeSomeDto
import org.tenshu.core.common.impl.MissingParameterDto
import org.tenshu.core.common.impl.NotFoundErrorDto
import org.tenshu.core.common.impl.RelationDataIdDto
import org.tenshu.core.common.impl.ResolveEntityDataIdDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.common.impl.StreamElementEndDto
import org.tenshu.core.common.impl.StreamElementRemovedDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.impl.StringDataIdDto
import org.tenshu.core.common.impl.TBooleanDto
import org.tenshu.core.common.impl.TBooleanRo
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.TByteArrayRo
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.impl.TIntegerRo
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.impl.TStringRo
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.impl.UnixInstantRo
import org.tenshu.core.common.impl.UnknownParameterDto
import org.tenshu.core.common.impl.WrongParameterDto
import org.tenshu.core.common.impl.array.FilterRo
import org.tenshu.core.common.impl.array.FindRo
import org.tenshu.core.common.impl.array.GetAllRo
import org.tenshu.core.common.impl.array.ListDropRo
import org.tenshu.core.common.impl.array.ListRo
import org.tenshu.core.common.impl.array.ListSearchRo
import org.tenshu.core.common.impl.array.ListSelectRo
import org.tenshu.core.common.impl.entity.EntitySelectRo
import org.tenshu.core.common.impl.maybe.MaybeSelectRo
import org.tenshu.core.common.impl.array.ListTakeRo
import org.tenshu.core.common.impl.array.SearchAllRo
import org.tenshu.core.common.impl.array.TraverseRo
import org.tenshu.core.common.impl.array.SortByRo
import org.tenshu.core.common.impl.array.StreamRo
import org.tenshu.core.common.impl.collection.TMapDto
import org.tenshu.core.common.impl.collection.TPairDto
import org.tenshu.core.common.impl.proxy.PendingDeferredPropertyDto
import org.tenshu.core.common.impl.proxy.PendingOnDemandPropertyDto
import org.tenshu.core.common.impl.maybe.IfEmptyErrorRo
import org.tenshu.core.common.impl.maybe.MaybeRo
import org.tenshu.core.common.impl.schema.CompositeSchemaDto
import org.tenshu.core.common.impl.schema.ConstantSchemaDto
import org.tenshu.core.common.impl.schema.DynamicPropertyRelationSchemaDto
import org.tenshu.core.common.impl.schema.ServiceDataRelationSchemaDto
import org.tenshu.core.common.impl.schema.FieldDto
import org.tenshu.core.common.impl.schema.GenericTypeDto
import org.tenshu.core.common.impl.schema.OperationSchemaDto
import org.tenshu.core.common.impl.schema.PropertyRelationSchemaDto
import org.tenshu.core.common.impl.schema.ReferenceSchemaDto
import org.tenshu.core.common.impl.schema.ServiceRelationSchemaDto
import org.tenshu.core.common.impl.schema.ServiceSchemaDto
import org.tenshu.core.common.impl.schema.TraitRequirementSchemaDto
import org.tenshu.core.common.impl.schema.TypeDto
import org.tenshu.core.common.impl.schema.TypeParameterDeclDto
import org.tenshu.core.common.impl.schema.TypeParameterDto
import org.tenshu.core.common.impl.schema.TypeVariableDto
import org.tenshu.core.common.impl.schema.UnionSchemaDto
import org.tenshu.core.common.impl.schema.UnionTypeDto
import org.tenshu.core.common.impl.schema.ValueSchemaDto
import org.tenshu.core.common.impl.schema.ValueTraitRequirementSchemaDto
import org.tenshu.core.common.impl.selector.DynamicPropertySelectorDto
import org.tenshu.core.common.impl.selector.FieldSelectorDto
import org.tenshu.core.common.impl.selector.PropertySelectorDto
import org.tenshu.core.common.impl.selector.ResolvedDynamicPropertySelectorDto
import org.tenshu.core.common.proxy.EntityDataIdProviderImpl
import org.tenshu.core.common.proxy.PendingDeferredProperty
import org.tenshu.core.common.proxy.PendingOnDemandProperty
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.EntityDataIdProvider
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.entity.ResolveEntityDataId
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.CompositeTypeReference
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.DynamicPropertyRelation
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.GenericData
import org.tenshu.core.schema.GenericType
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.TypeParameterDecl
import org.tenshu.core.schema.TypeVariable
import org.tenshu.core.schema.UnionSchema
import org.tenshu.core.schema.UnionType
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.Variance
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.PureOperation
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.core.trait.TraitRequirementSchema
import org.tenshu.core.trait.ValueTraitRequirement
import org.tenshu.core.trait.ValueTraitRequirementSchema
import org.tenshu.core.common.impl.maybe.GetRo as MaybeGetRo
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.app.impl.AnnounceSessionRo
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.ServiceVersionDto
import org.tenshu.core.common.schema.ServiceVersionMismatch
import org.tenshu.core.common.schema.ServiceVersionMismatchDto
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.SessionAdmissionDto
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.VersionVectorDto
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppGrantDataIdProvider
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.AppRegistrationDataIdProvider
import org.tenshu.core.app.AppService
import org.tenshu.core.app.OpenOutcome
import org.tenshu.core.app.OpenTarget
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.app.RegisterApp
import org.tenshu.core.app.RemoveApp
import org.tenshu.core.app.SetAppGrant
import org.tenshu.core.common.app.impl.AppGrantDataIdProviderImpl
import org.tenshu.core.common.app.impl.AppGrantDto
import org.tenshu.core.common.app.impl.AppGrantRo
import org.tenshu.core.common.app.impl.AppRegistrationDataIdProviderImpl
import org.tenshu.core.common.app.impl.AppRegistrationDto
import org.tenshu.core.common.app.impl.AppRegistrationRo
import org.tenshu.core.common.app.impl.AppServiceRo
import org.tenshu.core.common.app.impl.OpenOutcomeDto
import org.tenshu.core.common.app.impl.OpenTargetRequestDto
import org.tenshu.core.common.app.impl.OpenTargetRo
import org.tenshu.core.common.app.impl.OpenTargetUnavailableDto
import org.tenshu.core.common.app.impl.RegisterAppRo
import org.tenshu.core.common.app.impl.RemoveAppRo
import org.tenshu.core.common.app.impl.SetAppGrantRo
import org.tenshu.core.common.realmdata.impl.DeleteRealmDataRo
import org.tenshu.core.common.realmdata.impl.RealmDataServiceRo
import org.tenshu.core.realmdata.DeleteRealmData
import org.tenshu.core.realmdata.RealmDataService
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.schema.elementSchema
import org.tenshu.core.common.impl.relation.create

val coreCommonModule = tModule {
    type(
        interfaceClass = Data::class,
        dataSchema = Data.Schema,
        roFactory = { chain, context ->
            CompositeRo(chain, context, Data.Schema as CompositeSchema)
        }
    )
    type(Composite::class, Composite.Schema)
    type(Operation::class, OperationSchema.Schema)
    type(TValue::class, TValue.Schema)
    // ⚠ The `roFactory` is what lets a constant-valued property be *referenced* in a chain —
    // `filter<File> { category equalTo FileCategory.Image }` and the `FileCategoryOf` op's return
    // both build one. Without it the chain dies at build time with "No request object factory
    // registered for type: tenshu/core/value". See [TConstantRo] for why the placeholder is typed
    // `TConstant` rather than the concrete (usually sealed) constant family.
    type(
        interfaceClass = TConstant::class,
        dataSchema = TConstant.Schema,
        roFactory = { chain, context -> TConstantRo(chain, context, TConstant.Schema) },
    )
    type(TError::class, TError.Schema)
    type(TResult::class, TResult.Schema)

    composite(TError.Schema)
    composite(DataSchema.Schema)
    composite(GenericData.Schema)
    composite(Trait.Schema)
    composite(PureOperation.Schema)
    composite(DataIdProvider.Schema)
    composite(CompositeDataIdProvider.Schema)
    composite(RelationSchema.Schema)
    composite(StreamElement.Schema)
    composite(
        interfaceClass = TraitRequirementSchema::class,
        dataSchema = TraitRequirementSchema.Schema,
        dtoFactory = ::TraitRequirementSchemaDto
    )
    composite(
        interfaceClass = ValueTraitRequirementSchema::class,
        dataSchema = ValueTraitRequirementSchema.Schema,
        dtoFactory = ::ValueTraitRequirementSchemaDto
    )
    composite(TraitRequirement.Schema)
    composite(ValueTraitRequirement.Schema)

    composite(
        interfaceClass = HybridLogicalClock::class,
        dataSchema = HybridLogicalClock.Schema,
        dtoFactory = ::HybridLogicalClockDto
    )

    type(
        interfaceClass = SyncStrategy::class,
        dataSchema = SyncStrategy.Schema
    )
    constant(
        interfaceClass = SyncStrategy.Eager::class,
        dataSchema = SyncStrategy.Eager.schema,
        dtoFactory = { SyncStrategy.Eager }
    )
    constant(
        interfaceClass = SyncStrategy.OnDemand::class,
        dataSchema = SyncStrategy.OnDemand.schema,
        dtoFactory = { SyncStrategy.OnDemand }
    )

    type(
        interfaceClass = ConflictStrategy::class,
        dataSchema = ConflictStrategy.Schema
    )
    constant(
        interfaceClass = ConflictStrategy.LastWriterWins::class,
        dataSchema = ConflictStrategy.LastWriterWins.schema,
        dtoFactory = { ConflictStrategy.LastWriterWins }
    )
    constant(
        interfaceClass = ConflictStrategy.Merge::class,
        dataSchema = ConflictStrategy.Merge.schema,
        dtoFactory = { ConflictStrategy.Merge }
    )
    constant(
        interfaceClass = ConflictStrategy.Manual::class,
        dataSchema = ConflictStrategy.Manual.schema,
        dtoFactory = { ConflictStrategy.Manual }
    )

    composite(
        interfaceClass = Type::class,
        dataSchema = Type.Schema,
        dtoFactory = ::TypeDto
    )
    composite(
        interfaceClass = GenericType::class,
        dataSchema = GenericType.Schema,
        dtoFactory = ::GenericTypeDto
    )
    composite(
        interfaceClass = UnionType::class,
        dataSchema = UnionType.Schema,
        dtoFactory = ::UnionTypeDto
    )
    composite(
        interfaceClass = TypeVariable::class,
        dataSchema = TypeVariable.Schema,
        dtoFactory = ::TypeVariableDto
    )
    composite(
        interfaceClass = UnionSchema::class,
        dataSchema = UnionSchema.Schema,
        dtoFactory = ::UnionSchemaDto
    )
    composite(
        interfaceClass = TypeParameter::class,
        dataSchema = TypeParameter.Schema,
        dtoFactory = ::TypeParameterDto
    )
    composite(
        interfaceClass = TypeParameterDecl::class,
        dataSchema = TypeParameterDecl.Schema,
        dtoFactory = ::TypeParameterDeclDto
    )
    composite(
        interfaceClass = Field::class,
        dataSchema = Field.Schema,
        dtoFactory = ::FieldDto
    )
    type(
        interfaceClass = Variance::class,
        dataSchema = Variance.Schema
    )
    constant(
        interfaceClass = Variance.Invariant::class,
        dataSchema = Variance.Invariant.schema,
        dtoFactory = { Variance.Invariant }
    )
    constant(
        interfaceClass = Variance.Covariant::class,
        dataSchema = Variance.Covariant.schema,
        dtoFactory = { Variance.Covariant }
    )
    constant(
        interfaceClass = Variance.Contravariant::class,
        dataSchema = Variance.Contravariant.schema,
        dtoFactory = { Variance.Contravariant }
    )

    composite(
        interfaceClass = CompositeSchema::class,
        dataSchema = CompositeSchema.Schema,
        dtoFactory = ::CompositeSchemaDto
    )
    composite(
        interfaceClass = OperationSchema::class,
        dataSchema = OperationSchema.Schema,
        dtoFactory = ::OperationSchemaDto
    )
    composite(
        interfaceClass = ValueSchema::class,
        dataSchema = ValueSchema.Schema,
        dtoFactory = ::ValueSchemaDto
    )
    composite(
        interfaceClass = ConstantSchema::class,
        dataSchema = ConstantSchema.Schema,
        dtoFactory = ::ConstantSchemaDto
    )
    composite(
        interfaceClass = ReferenceSchema::class,
        dataSchema = ConstantSchema.Schema,
        dtoFactory = ::ReferenceSchemaDto
    )
    composite(
        interfaceClass = RelationSchema.PropertyRelationSchema::class,
        dataSchema = RelationSchema.Schema as CompositeSchema,
        dtoFactory = ::PropertyRelationSchemaDto
    )
    composite(
        interfaceClass = RelationSchema.DynamicPropertyRelationSchema::class,
        dataSchema = RelationSchema.DynamicPropertyRelationSchema.Schema,
        dtoFactory = { fields ->
            DynamicPropertyRelationSchemaDto(
                fieldValues = fields,
                callstack = ChainTemplate<Data, Data> { error("No callstack available for parsed schema") }
            )
        }
    )
    composite(
        interfaceClass = RelationSchema.ServiceRelationSchema::class,
        dataSchema = RelationSchema.ServiceRelationSchema.Schema,
        dtoFactory = ::ServiceRelationSchemaDto
    )
    composite(
        interfaceClass = RelationSchema.ServiceDataRelationSchema::class,
        dataSchema = RelationSchema.ServiceDataRelationSchema.Schema,
        dtoFactory = ::ServiceDataRelationSchemaDto
    )
    // The runtime relation *instances* (distinct from the schema-descriptor DTOs
    // above) cross the wire whenever a property handle is shipped raw rather than
    // unwrapped. `sortBy(File.LastModifiedAt)` puts a `StaticPropertyRelation`
    // straight into the SortBy SELECTOR param (see `SortByRo.sortByProperty`),
    // unlike `select { relations(handle) }` which unwraps to a `PropertySelector`.
    // Without these registrations the proxy can't resolve the type id and
    // `ProxyGateway.call` fails to parse the callstack. Only `commonTest`'s
    // serialize→parse round-trip catches it: in-process the local connection
    // passes live objects and never hits the BinaryParser.
    composite(
        interfaceClass = StaticPropertyRelation::class,
        dataSchema = StaticPropertyRelation.Schema,
        dtoFactory = { fields ->
            StaticPropertyRelation.create(
                reference = fields.first() as PropertyTypeReference
            )
        }
    )
    composite(
        interfaceClass = DynamicPropertyRelation::class,
        dataSchema = DynamicPropertyRelation.Schema,
        dtoFactory = { fields ->
            @Suppress("UNCHECKED_CAST")
            DynamicPropertyRelation.create(
                reference = fields.first() as DynamicPropertyTypeReference,
                params = fields.getOrNull(1) as? TList<Data>
            )
        }
    )
    composite(
        interfaceClass = ServiceSchema::class,
        dataSchema = ServiceSchema.Schema,
        dtoFactory = ::ServiceSchemaDto
    )
    parameterisedComposite(
        interfaceClass = Maybe.Some::class,
        dataSchema = Maybe.Some.Schema,
        dtoFactory = { MaybeSomeDto<Data>(it) },
        roFactory = { chain, context, type ->
            MaybeRo<Data>(
                requestChain = chain,
                context = context,
                schema = Maybe.Some.Schema,
                elementType = type.elementSchema()
            )
        }
    )
    parameterisedComposite(
        interfaceClass = Maybe.Empty::class,
        dataSchema = Maybe.Empty.Schema,
        dtoFactory = { MaybeEmptyDto<Data>(it) },
        roFactory = { chain, context, type ->
            MaybeRo<Data>(
                requestChain = chain,
                context = context,
                schema = Maybe.Empty.Schema,
                elementType = type.elementSchema()
            )
        }
    )

    parameterisedType(
        interfaceClass = Maybe::class,
        dataSchema = org.tenshu.core.schema.composite(Maybe.ID),
        roFactory = { chain, context, type ->
            MaybeRo<Data>(
                requestChain = chain,
                context = context,
                schema = org.tenshu.core.schema.composite(Maybe.ID),
                elementType = type.elementSchema()
            )
        }
    )

    operation(
        interfaceClass = Get::class,
        protocolSchema = Get.Schema,
        roFactory = { chain, context ->
            MaybeGetRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )

    // The value terminal — see `org.tenshu.core.Resolve` for why a scalar chain had no way to end.
    operation(
        interfaceClass = Resolve::class,
        protocolSchema = Resolve.Schema,
        roFactory = { chain, context ->
            ResolveRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )

    operation(
        interfaceClass = IfEmptyError::class,
        protocolSchema = IfEmptyError.Schema,
        roFactory = { chain, context ->
            IfEmptyErrorRo<Data>(
                requestChain = chain,
                context = context,
            )
        }
    )

    composite(
        interfaceClass = TResult.Success::class,
        dataSchema = TResult.Success.Schema,
        dtoFactory = { ResultSuccessDto<Data>(it) }
    )
    composite(
        interfaceClass = TResult.Failure::class,
        dataSchema = TResult.Failure.Schema,
        dtoFactory = { ResultFailureDto<Data>(it) }
    )
    composite(
        interfaceClass = InvalidCallerError::class,
        dataSchema = InvalidCallerError.Schema,
        dtoFactory = ::InvalidCallerErrorDto
    )
    composite(
        interfaceClass = GenericError::class,
        dataSchema = GenericError.Schema,
        dtoFactory = ::GenericErrorDto
    )
    composite(
        interfaceClass = UnannouncedSessionError::class,
        dataSchema = UnannouncedSessionError.Schema,
        dtoFactory = ::UnannouncedSessionErrorDto
    )
    // E3 (§9.3): the cut-off/skew answer. ⚠ Registered here because it must
    // cross the wire — an unregistered error type would itself fail to parse on
    // the client, turning the very case this names back into error/internal.
    composite(
        interfaceClass = UnsupportedOperationError::class,
        dataSchema = UnsupportedOperationError.Schema,
        dtoFactory = ::UnsupportedOperationErrorDto
    )
    composite(
        interfaceClass = NotFoundError::class,
        dataSchema = NotFoundError.Schema,
        dtoFactory = ::NotFoundErrorDto
    )
    composite(
        interfaceClass = MissingParameter::class,
        dataSchema = MissingParameter.Schema,
        dtoFactory = ::MissingParameterDto
    )
    composite(
        interfaceClass = UnknownParameter::class,
        dataSchema = UnknownParameter.Schema,
        dtoFactory = ::UnknownParameterDto
    )
    composite(
        interfaceClass = WrongParameter::class,
        dataSchema = WrongParameter.Schema,
        dtoFactory = ::WrongParameterDto
    )
    composite(
        interfaceClass = InternalError::class,
        dataSchema = InternalError.Schema,
        dtoFactory = ::InternalErrorDto
    )
    composite(
        interfaceClass = InvalidParameter::class,
        dataSchema = InvalidParameter.Schema,
        dtoFactory = ::InvalidParameterDto
    )
    composite(
        interfaceClass = StreamElement.Value::class,
        dataSchema = StreamElement.Value.Schema,
        dtoFactory = ::StreamElementValueDto
    )
    composite(
        interfaceClass = StreamElement.Removed::class,
        dataSchema = StreamElement.Removed.Schema,
        dtoFactory = ::StreamElementRemovedDto
    )
    composite(
        interfaceClass = StreamElement.End::class,
        dataSchema = StreamElement.End.Schema,
        dtoFactory = ::StreamElementEndDto
    )
    composite(
        interfaceClass = TMap::class,
        dataSchema = TMap.Schema,
        dtoFactory = { fields -> TMapDto<Data, Data>(fields) }
    )
    composite(
        interfaceClass = TPair::class,
        dataSchema = TPair.Schema,
        dtoFactory = { fields -> TPairDto<Data, Data>(fields) }
    )

    composite(
        interfaceClass = Entity::class,
        dataSchema = Entity.Schema,
        dtoFactory = { fields -> EntityDto<Data>(fields) }
    )

    composite(
        interfaceClass = PendingOnDemandProperty::class,
        dataSchema = PendingOnDemandProperty.Schema,
        dtoFactory = ::PendingOnDemandPropertyDto
    )

    composite(
        interfaceClass = PendingDeferredProperty::class,
        dataSchema = PendingDeferredProperty.Schema,
        dtoFactory = ::PendingDeferredPropertyDto
    )

    value(
        interfaceClass = TByteArray::class,
        dataSchema = TByteArray.Schema,
        dtoFactory = { TByteArrayDto(it) },
        roFactory = ::TByteArrayRo
    )
    value(
        interfaceClass = TString::class,
        dataSchema = TString.Schema,
        dtoFactory = ::TStringDto,
        roFactory = ::TStringRo
    )
    value(
        interfaceClass = UnixInstant::class,
        dataSchema = UnixInstant.Schema,
        dtoFactory = ::UnixInstantDto,
        // Chain-recording RO so a selector can read a timestamp field off an
        // element (`sortByDescending { timestamp }`) — without it the field
        // access dies with "No request object factory registered" at chain
        // build time.
        roFactory = ::UnixInstantRo
    )
    value(
        interfaceClass = TInteger::class,
        dataSchema = TInteger.Schema,
        dtoFactory = ::TIntegerDto,
        roFactory = ::TIntegerRo
    )
    value(
        interfaceClass = TBoolean::class,
        dataSchema = TBoolean.Schema,
        dtoFactory = ::TBooleanDto,
        roFactory = ::TBooleanRo
    )

    type(
        interfaceClass = DataId::class,
        dataSchema = DataId.Schema
    )
    composite(
        interfaceClass = DataId.StringDataId::class,
        dataSchema = DataId.StringDataId.Schema,
        dtoFactory = ::StringDataIdDto
    )
    composite(
        interfaceClass = DataId.LongDataId::class,
        dataSchema = DataId.LongDataId.Schema,
        dtoFactory = ::LongDataIdDto
    )
    composite(
        interfaceClass = DataId.CompositeEntityDataId::class,
        dataSchema = DataId.CompositeEntityDataId.Schema,
        dtoFactory = ::CompositeEntityDataIdDto
    )
    composite(
        interfaceClass = DataId.EntityFieldDataId::class,
        dataSchema = DataId.EntityFieldDataId.Schema,
        dtoFactory = ::EntityFieldDataIdDto
    )
    composite(
        interfaceClass = DataId.RelationDataId::class,
        dataSchema = DataId.RelationDataId.Schema,
        dtoFactory = ::RelationDataIdDto
    )
    composite(
        interfaceClass = DataId.EntityPropertyDataId::class,
        dataSchema = DataId.EntityPropertyDataId.Schema,
        dtoFactory = ::EntityPropertyDataIdDto
    )
    type(
        interfaceClass = Reference::class,
        dataSchema = Reference.Schema
    )
    type(
        interfaceClass = CompositeTypeReference::class,
        dataSchema = CompositeTypeReference.Schema
    )
    type(
        interfaceClass = EntityReference::class,
        dataSchema = EntityReference.Schema
    )
    type(
        interfaceClass = FieldReference::class,
        dataSchema = FieldReference.Schema
    )
    type(
        interfaceClass = ServiceTypeReference::class,
        dataSchema = ServiceTypeReference.Schema
    )
    type(
        interfaceClass = PropertyTypeReference::class,
        dataSchema = PropertyTypeReference.Schema
    )
    type(
        interfaceClass = DynamicPropertyTypeReference::class,
        dataSchema = DynamicPropertyTypeReference.Schema
    )

    constant(
        interfaceClass = FieldReference::class,
        dataSchema = FieldReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.FieldReferenceDto("") }
    )
    constant(
        interfaceClass = PropertyTypeReference::class,
        dataSchema = PropertyTypeReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto("") }
    )
    constant(
        interfaceClass = DynamicPropertyTypeReference::class,
        dataSchema = DynamicPropertyTypeReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.DynamicPropertyTypeReferenceDto("") }
    )
    constant(
        interfaceClass = EntityReference::class,
        dataSchema = EntityReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.EntityReferenceDto("") }
    )
    constant(
        interfaceClass = CompositeTypeReference::class,
        dataSchema = CompositeTypeReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.CompositeTypeReferenceDto("") }
    )
    constant(
        interfaceClass = ServiceTypeReference::class,
        dataSchema = ServiceTypeReference.Schema,
        dtoFactory = { org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto("") }
    )

    parameterisedType(
        interfaceClass = TList::class,
        dataSchema = TList.Schema,
        roFactory = { chain, context, type ->
            ListRo<Data>(
                context = context,
                requestChain = chain,
                elementType = type.elementSchema()
            )
        }
    )

    type(
        interfaceClass = Selector::class,
        dataSchema = Selector.Schema
    )
    composite(
        interfaceClass = Selector.FieldSelector::class,
        dataSchema = Selector.FieldSelector.Schema,
        dtoFactory = { fields -> FieldSelectorDto<Data>(fieldReference = fields.first() as FieldReference) }
    )
    composite(
        interfaceClass = Selector.PropertySelector::class,
        dataSchema = Selector.PropertySelector.Schema,
        dtoFactory = { fields ->
            @Suppress("UNCHECKED_CAST")
            PropertySelectorDto<Data>(
                propertyReference = fields.first() as PropertyTypeReference,
                nested = fields.getOrNull(1) as? TList<Selector<*>>,
            )
        }
    )
    composite(
        interfaceClass = Selector.DynamicPropertySelector::class,
        dataSchema = Selector.DynamicPropertySelector.Schema,
        dtoFactory = { fields ->
            @Suppress("UNCHECKED_CAST")
            DynamicPropertySelectorDto<Data>(
                dynamicPropertyReference = fields.first() as DynamicPropertyTypeReference,
                params = (fields.getOrNull(1) as? List<Any>?)?.toTList()
            )
        }
    )
    composite(
        interfaceClass = Selector.DynamicPropertySelector.Resolved::class,
        dataSchema = Selector.DynamicPropertySelector.Resolved.Schema,
        dtoFactory = { fields ->
            @Suppress("UNCHECKED_CAST")
            ResolvedDynamicPropertySelectorDto<Data>(
                dynamicPropertyReference = fields.first() as DynamicPropertyTypeReference,
                params = (fields.getOrNull(1) as? List<Any>?)?.toTList(),
                deferred = fields[2] as TBoolean,
            )
        }
    )

    operation(
        interfaceClass = Select::class,
        protocolSchema = Select.Schema,
        roFactory = { chain, context ->
            ListSelectRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = MaybeSelect::class,
        protocolSchema = MaybeSelect.Schema,
        roFactory = { chain, context ->
            MaybeSelectRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = EntitySelect::class,
        protocolSchema = EntitySelect.Schema,
        roFactory = { chain, context ->
            EntitySelectRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Search::class,
        protocolSchema = Search.Schema,
        roFactory = { chain, context ->
            ListSearchRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = SearchAll::class,
        protocolSchema = SearchAll.Schema,
        roFactory = { chain, context ->
            SearchAllRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Traverse::class,
        protocolSchema = Traverse.Schema,
        roFactory = { chain, context ->
            TraverseRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Filter::class,
        protocolSchema = Filter.Schema,
        roFactory = { chain, context ->
            FilterRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Find::class,
        protocolSchema = Find.Schema,
        roFactory = { chain, context ->
            FindRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = GetAll::class,
        protocolSchema = GetAll.Schema,
        roFactory = { chain, context ->
            GetAllRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Stream::class,
        protocolSchema = Stream.Schema,
        roFactory = { chain, context ->
            StreamRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = EqualTo::class,
        protocolSchema = EqualTo.Schema,
        roFactory = { chain, context ->
            EqualToRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = OneOf::class,
        protocolSchema = OneOf.Schema,
        roFactory = { chain, context ->
            OneOfRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = Contains::class,
        protocolSchema = Contains.Schema,
        roFactory = { chain, context ->
            ContainsRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = InRange::class,
        protocolSchema = InRange.Schema,
        roFactory = { chain, context ->
            InRangeRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = SubstringAfterLast::class,
        protocolSchema = SubstringAfterLast.Schema,
        roFactory = { chain, context ->
            SubstringAfterLastRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = Lowercase::class,
        protocolSchema = Lowercase.Schema,
        roFactory = { chain, context ->
            LowercaseRo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = IsA::class,
        protocolSchema = IsA.Schema,
        roFactory = { chain, context ->
            IsARo(
                requestChain = chain,
                context = context,
            )
        }
    )
    operation(
        interfaceClass = EndStream::class,
        protocolSchema = EndStream.Schema
    )
    operation(
        interfaceClass = Take::class,
        protocolSchema = Take.Schema,
        roFactory = { chain, context ->
            ListTakeRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = SortBy::class,
        protocolSchema = SortBy.Schema,
        roFactory = { chain, context ->
            SortByRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Drop::class,
        protocolSchema = Drop.Schema,
        roFactory = { chain, context ->
            ListDropRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )
    operation(
        interfaceClass = Count::class,
        protocolSchema = Count.Schema,
        roFactory = { chain, context ->
            ListCountRo<Data>(
                requestChain = chain,
                context = context
            )
        }
    )

    operation(
        interfaceClass = ResolveEntityDataId::class,
        protocolSchema = ResolveEntityDataId.Schema,
        dtoFactory = ::ResolveEntityDataIdDto
    )

    operation(
        interfaceClass = GetStringDataId::class,
        protocolSchema = GetStringDataId.Schema,
        dtoFactory = ::GetStringDataIdDto
    )

    trait(
        dataSchema = Entity.Schema,
        traitSchema = EntityDataIdProvider.Schema,
        traitFactory = { context, _ -> EntityDataIdProviderImpl(context) }
    )

    trait(
        dataSchema = Data.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = schema
                override val traitRequirements: TList<TraitRequirement> =
                    SimpleTList(emptyList())

                @Suppress("UNCHECKED_CAST")
                override fun <T : Data> getOptionalField(reference: String): T? {
                    return when (reference) {
                        Syncable.Fields.STRATEGY -> (composite.getOptionalField<Data>(reference)
                            ?: SyncStrategy.Eager) as T

                        Syncable.Fields.CONFLICT_STRATEGY -> (composite.getOptionalField<Data>(
                            reference
                        ) ?: ConflictStrategy.LastWriterWins) as T

                        else -> composite.getOptionalField(reference)
                    }
                }
            }
        }
    )

    trait(
        dataSchema = Data.Schema,
        traitSchema = DataIdProvider.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : DataIdProvider<Data>, Trait by (object : Trait, Composite by composite {
                override val schema get() = schema as org.tenshu.core.entity.DataIdProviderSchema
                override val traitRequirements: TList<TraitRequirement> =
                    SimpleTList(emptyList())
            }) {
                override val schema get() = schema as org.tenshu.core.entity.DataIdProviderSchema
            }
        }
    )

    // ---- App registration & permissions (control-app-registration.md) -------
    // AppRegistration / AppGrant are platform synced data living in :core (not a
    // :rei-service module — see AppService KDoc + control-app-registration.md §7).
    // Registered here so they are picked up by both createDefaultSchemaRegistry
    // (production) and createCoreTestRegistry (tests) with no per-app wiring; the
    // proxy op implementations are in `implementationFactory` and the relation
    // maintainer in `initAppService`.
    composite(
        interfaceClass = AppRegistration::class,
        dataSchema = AppRegistration.Schema,
        dtoFactory = ::AppRegistrationDto,
        roFactory = { chain, ctx ->
            AppRegistrationRo(requestChain = chain, context = ctx, schema = AppRegistration.Schema)
        }
    )
    composite(
        interfaceClass = AppGrant::class,
        dataSchema = AppGrant.Schema,
        dtoFactory = ::AppGrantDto,
        roFactory = { chain, ctx ->
            AppGrantRo(requestChain = chain, context = ctx, schema = AppGrant.Schema)
        }
    )

    // Schema-only registration populates the `relations` map from the service
    // schema's relation schemas (chain resolution needs it); the typed form
    // below adds the class→type mapping + client RO factory. Mirrors the
    // preference service's common/client split, collapsed into one module here.
    service(AppService.Schema)
    service(
        interfaceClass = AppService::class,
        serviceSchema = AppService.Schema,
        roFactory = ::AppServiceRo
    )

    // Cross-app open (control-cross-app-open.md TT-1a). ⚠⚠ All THREE composites, not just the
    // return type: `OpenTargetRequest` only ever travels INTO the receiving app and
    // `OpenTargetUnavailable` only appears when something fails, so both are the kind of type a
    // module forgets and a test discovers at the worst moment (MQ-5a lost 14 tests to exactly
    // this on `IntentCandidate`).
    composite(
        interfaceClass = OpenOutcome::class,
        dataSchema = OpenOutcome.Schema,
        dtoFactory = ::OpenOutcomeDto,
    )
    composite(
        interfaceClass = OpenTargetRequest::class,
        dataSchema = OpenTargetRequest.Schema,
        dtoFactory = ::OpenTargetRequestDto,
    )
    composite(
        interfaceClass = OpenTargetUnavailable::class,
        dataSchema = OpenTargetUnavailable.Schema,
        dtoFactory = ::OpenTargetUnavailableDto,
    )

    operation(
        interfaceClass = OpenTarget::class,
        protocolSchema = OpenTarget.Schema,
        roFactory = ::OpenTargetRo
    )
    operation(
        interfaceClass = RegisterApp::class,
        protocolSchema = RegisterApp.Schema,
        roFactory = ::RegisterAppRo
    )
    operation(
        interfaceClass = RemoveApp::class,
        protocolSchema = RemoveApp.Schema,
        roFactory = ::RemoveAppRo
    )
    operation(
        interfaceClass = SetAppGrant::class,
        protocolSchema = SetAppGrant.Schema,
        roFactory = ::SetAppGrantRo
    )
    // AnnounceSession (`core-schema-evolution.md` §6.8): session-start schema
    // negotiation on the same receiver + lifecycle moment as RegisterApp. Its
    // request/reply composites are the frozen set registered below.
    operation(
        interfaceClass = AnnounceSession::class,
        protocolSchema = AnnounceSession.Schema,
        roFactory = ::AnnounceSessionRo
    )

    trait(
        dataSchema = AppRegistration.Schema,
        traitSchema = AppRegistrationDataIdProvider.Schema,
        traitFactory = { context, schema ->
            AppRegistrationDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
    trait(
        dataSchema = AppGrant.Schema,
        traitSchema = AppGrantDataIdProvider.Schema,
        traitFactory = { context, schema ->
            AppGrantDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // Realm-data reset (`control-data-reset.md` R1). Registered AFTER AppService
    // deliberately: `SchemaRegistry.owningServiceOf` attributes a config's types to
    // the config's FIRST-declared service, so `:core`'s composites must keep
    // attributing to AppService (the frozen-forever, never-fencing announce channel
    // — schema-evolution §4.1.3). A relation-less verb receiver; see its KDoc.
    service(RealmDataService.Schema)
    service(
        interfaceClass = RealmDataService::class,
        serviceSchema = RealmDataService.Schema,
        roFactory = ::RealmDataServiceRo
    )
    operation(
        interfaceClass = DeleteRealmData::class,
        protocolSchema = DeleteRealmData.Schema,
        roFactory = ::DeleteRealmDataRo
    )

    // Schema-versioning envelope (`core-schema-evolution.md` §6.2) — ⚠ FROZEN
    // FOREVER. These four composites are the negotiation channel (the announce
    // op's request/reply and the block version stamp); their wire shape must
    // never change, and additions go in NEW composite ids. Registered here so
    // both sides of every transport parse them — the wire-registration gap
    // (in-process tests pass live objects and never hit the BinaryParser) is
    // covered by SchemaVersioningWireTest's serialize→parse round-trips.
    composite(
        interfaceClass = ServiceVersion::class,
        dataSchema = ServiceVersion.Schema,
        dtoFactory = ::ServiceVersionDto,
    )
    composite(
        interfaceClass = VersionVector::class,
        dataSchema = VersionVector.Schema,
        dtoFactory = ::VersionVectorDto,
    )
    composite(
        interfaceClass = ServiceVersionMismatch::class,
        dataSchema = ServiceVersionMismatch.Schema,
        dtoFactory = ::ServiceVersionMismatchDto,
    )
    composite(
        interfaceClass = SessionAdmission::class,
        dataSchema = SessionAdmission.Schema,
        dtoFactory = ::SessionAdmissionDto,
    )
}
