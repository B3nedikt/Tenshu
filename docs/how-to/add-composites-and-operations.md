# How-To: Adding New Composites and Operations

This guide provides a step-by-step example of how to extend the Tenshu Platform with new domain
objects (Composites) and actions (Operations).

## 0. The "One Interface Per File" Rule

To maintain modularity and a clean project structure:

* **Strictly One Interface Per File**: Every `Composite`, `Operation`, or `Service` MUST be defined
  in its own file.
* **No Nesting**: Do NOT nest these interfaces within other classes or each other.

## 1. Defining a Composite

Composites are the primary data objects in the Tenshu type system.

### Step 1: Create the Interface (`common` module)

Define the interface and its schema.

**Rules for Interfaces**:

* **One File Per Composite**: Every `Composite` MUST be defined in its own file. Do NOT nest
  `Composite` interfaces.
* **Tenshu Type Returns**: All properties MUST return Tenshu types (e.g., `TString`, `TByteArray`,
  `TInteger`) rather than primitive Kotlin types. The `.get()` conversion should happen only at the
  consumption layer.
* **Interface Accessors**: All property accessors using `getField` MUST live in the interface itself
  to ensure consistency across DTO and RO implementations.
* **Formatting**: Property accessors should be formatted with the `get()` on a new line.

```kotlin
interface Device : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    val name: TString
        get() = getField(Fields.NAME)

    companion object {
        const val ID = "tenshu/identity/device"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.NAME)
            }
        )

        /**
         * Standard factory for creating instances.
         * Parameter Order: context: TContext MUST be the first parameter.
         */
        fun create(context: TContext, deviceId: String, name: String): Device =
            context.create(ID, listOf(deviceId.toTString(context), name.toTString(context)))
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val NAME = "$ID/name"
    }
}
```

### Step 2: Expose Properties (Dual Extension Pattern)

To ensure properties work on both plain DTOs and platform-managed `Entity` wrappers, always provide
dual extensions.

```kotlin
// In the same file as the interface
val Device.deviceIdString: String get() = deviceId.get()
val Entity<out Device>.deviceIdString: String get() = getFieldValue<TString>(Device.Fields.DEVICE_ID).get()
```

### Step 3: Create the DTO (`common` module)

The DTO is a simple wrapper around the raw field values used for transport.

**Rules for DTOs**:

* **Visibility**: MUST be marked as `internal`.
* **Inheritance**: MUST inherit directly from `CompositeDto`.
* **Constructor**: MUST provide exactly `constructor(fieldValues: List<Data?>)`.

```kotlin
internal class DeviceDto(fieldValues: List<Data?>) : Device, CompositeDto(fieldValues)
```

---

## 2. Defining an Operation

Operations represent actions that can be performed on or by services.

### Step 1: Create the Interface (`common` module)

Define the operation signature and its schema.

**Rules for Operations**:

* **DSL Accessor**: Always provide a DSL-like extension function on the target type (or `TContext`)
  to make the operation discoverable and easy to use.
* **Return Type**: Operations MUST return `TResult<T>` or another Tenshu type (`TList`, `Data`,
  etc.).
* **Don't take `TContext` as a parameter** on a DSL accessor whose receiver is a [Data] /
  [Composite] / [Service] (i.e. anything obtained through the request DSL). Every RO produced by
  the request chain implements both `Data` and `TContext`, so derive it via `val context =
  this as TContext` inside the function body if you need it (e.g. to call
  `value.toTString(context)` or invoke an adapter). Threading `TContext` through to the call site
  is noise — callers don't have a meaningful choice of which context to pass, and it crowds out
  the actually-meaningful arguments. The cast is safe by construction: a DSL accessor that's
  called on something *not* obtained through the DSL has bigger problems than the
  `ClassCastException`. (Pattern: `Data.findOperation(...)` in
  `:core/.../OperationResolver.kt`; preference-service's `set` / `setForDevice` extensions for a
  multi-arg example that also needs adapter conversion.)

```kotlin
// Extension function for DSL usage
@Suppress("UNCHECKED_CAST")
fun <T : Data> TList<T>.drop(n: Int): TList<T> {
    val operation = operations.find { it.schema.id.get() == Drop.ID } as? Drop<T>
        ?: error("Drop operation not found. Available: ${operations.map { it.schema.id.get() }}")
    return operation.drop(n)
}

interface Drop<T : Data> : Operation {

    override val schema: OperationSchema get() = Schema

    fun drop(n: Int): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/drop"

        val Schema = operation(
            id = ID,
            params = {
                int(Params.N)
            },
            returnType = listType()
        )
    }

    object Params {
        const val N = "$ID/n"
    }
}
```

### Step 2: Create the RO (`client` module)

The Request Object (RO) is responsible for assembling the `RequestChain`.

**Rules for ROs**:

* **Visibility**: MUST be marked as `internal`.
* **Constructor**: MUST follow parameter sequence: `requestChain: RequestChain`, then
  `context: TContext`.
* **One Class Per File**: Every RO MUST be defined in its own file.
* **Organized Subpackages**: For operations belonging to a specific type (e.g., `TList` or `Maybe`),
  ROs should be placed in a subpackage named after that type (e.g., `...impl.array` or
  `...impl.maybe`).
* **No Direct Creation**: ROs MUST NOT construct other RO classes directly. Use
  `context.roRegistry().createRequestObject(...)`.

```kotlin
internal class LoginRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Login {

    override val schema: OperationSchema get() = Login.Schema

    override fun login(email: String, password: String): TResult<Session> {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    Login.Params.EMAIL to email.toTString(context),
                    Login.Params.PASSWORD to password.toTString(context)
                ),
                schema = schema
            )
        )
        return nextChain.getResult() as TResult<Session>
    }
}
```

### Step 3: Create the Implementation (`proxy` module)

**Rules for Implementations**:

* **Pure vs. Proxy**:
    * Use `PureOperationImplementation` for side-effect-free logic (metadata, calculations).
    * Use `ProxyOperationImplementation` for logic requiring `ExecutionContext` (Database, Streams).

```kotlin
internal class LoginImplementation(
    private val authProvider: AuthProvider
) : ProxyOperationImplementation {

    override fun <T : Data> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): TResult<T> {
        val email = params.require<TString>(Login.Params.EMAIL).get()
        val password = params.require<TString>(Login.Params.PASSWORD).get()

        return authProvider.login(email, password) as TResult<T>
    }
}
```

---

## 3. Registration

Register the factories in your service's `ModuleConfiguration`. Registration order does not matter.

```kotlin
// In Common ModuleConfiguration
composite(Device::class, Device.Schema, ::DeviceDto)

// In Client ModuleConfiguration
operation(Login.Schema) { chain, context -> LoginRo(chain, context) }

// In Proxy ModuleConfiguration
operation(Login.Schema) { LoginImplementation(get()) }
```

---

## 4. Defining a Service

Services are top-level components that group related operations.

**Rules for Services**:

* **One File Per Service**: Like Composites and Operations, every `Service` interface MUST be in its
  own file.
* **Schema Registration**: Services must be registered using the `service()` DSL in
  `ModuleConfiguration`.

```kotlin
interface IdentityService : Service {
    companion object {
        const val ID = "tenshu/identity/service"
        val Schema = service(id = ID)
    }
}
```
