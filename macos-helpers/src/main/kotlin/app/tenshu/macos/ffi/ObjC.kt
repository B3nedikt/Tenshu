package app.tenshu.macos.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal Objective-C runtime bridge over `java.lang.foreign` — enough to build and
 * drive AppKit/AVFoundation objects from Kotlin without a JNI shim or a subprocess.
 *
 * **Why this exists.** macOS camera access from a *spawned helper* makes the JVM the
 * responsible TCC subject and gets it SIGKILLed on the first grant
 * (`docs/proposals/desktop-qr-scanning.md` §9). Opening the camera **in-process** removes
 * the crash entirely, because the app is then both the accessor and the responsible
 * subject — the ordinary native-app situation. Doing that from the JVM means calling
 * Objective-C, and this is the layer that makes those calls expressible. §13 P1.
 *
 * ### The three things that make ObjC-over-FFM work
 *
 * 1. **`objc_msgSend` must be bound per signature, and never as variadic.** Its C
 *    prototype is variadic, but the *dispatched* method is not: on arm64 the callee reads
 *    its arguments from registers per AAPCS, while a variadic binding would pass them on
 *    the stack — the call then reads garbage. So every distinct signature gets its own
 *    non-variadic downcall handle, cached in [handles]. Plain `objc_msgSend` covers the
 *    pointer/integer/void calls, which is most of them; a **struct return** needs
 *    [msgSendReturningStruct] and its architecture split — see there.
 * 2. **A pointer returned from a downcall is zero-length.** FFM can't know how much memory
 *    a returned `void*` addresses, so reading through it requires
 *    [MemorySegment.reinterpret] first. [reinterpretUnbounded] is that call, isolated so
 *    the unsafety is named rather than scattered.
 * 3. **Delegates are classes registered at runtime.** AVFoundation hands frames to a
 *    delegate object, so we synthesise one: `objc_allocateClassPair` + `class_addMethod`
 *    with an FFM *upcall stub* as the IMP ([registerClass]). The stub — and therefore the
 *    arena backing it — must outlive every call the runtime makes into it, which is why
 *    registration is process-global and one-shot per name.
 *
 * ### Lifetime and threading
 *
 * Everything cached here (class handles, selectors, upcall stubs, interned `NSString`s for
 * framework constants) lives in [Arena.global] and is never freed — these are process-wide
 * runtime facts, not per-call allocations. Anything with a real lifetime (sessions,
 * buffers, per-call strings) belongs in a caller-owned [Arena], and ObjC objects the caller
 * `alloc`s are +1 and need [release]; autoreleased returns should be wrapped in
 * [autoreleasePool].
 *
 * The bridge itself is thread-agnostic: `objc_msgSend` is just a call. Callbacks arrive on
 * whatever queue the framework was given, *not* the AWT thread, so nothing here assumes a
 * `CFRunLoop` is being pumped.
 *
 * Every entry point returns a null [MemorySegment] (see [isNull]) rather than throwing when
 * the runtime says "no such class/selector", so a caller on a non-macOS host — where
 * [isAvailable] is false — degrades instead of exploding.
 */
internal object ObjC {

    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()

    private val isMac: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** Process-wide arena for runtime facts that must never be freed (see class KDoc). */
    private val global: Arena = Arena.global()

    private val objc: SymbolLookup? by lazy {
        if (linker == null || !isMac) null
        else runCatching { SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", global) }
            .getOrNull()
    }

    /** Whether Objective-C can be reached from here — macOS with a usable FFM linker. */
    val isAvailable: Boolean get() = objc != null

    /**
     * The platform linker, for a sibling that binds **plain C** rather than `objc_msgSend`
     * ([CoreMedia]'s `CMTimeMakeWithSeconds`, `CACurrentMediaTime`). Null off macOS, so those
     * callers degrade the same way everything else here does.
     */
    fun linkerOrNull(): Linker? = if (isAvailable) linker else null

    // ── Downcall plumbing ─────────────────────────────────────────────────────────

    private val handles = ConcurrentHashMap<String, MethodHandle>()

    private fun objcHandle(symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
        val lookup = objc ?: return null
        val key = "$symbol/$descriptor"
        handles[key]?.let { return it }
        val address = lookup.find(symbol).orElse(null) ?: return null
        val handle = linker!!.downcallHandle(address, descriptor)
        handles[key] = handle
        return handle
    }

    private val ptr = ValueLayout.ADDRESS
    private val i64 = ValueLayout.JAVA_LONG
    private val i8 = ValueLayout.JAVA_BYTE

    /**
     * `objc_msgSend` bound for [argCount] pointer arguments after `(self, _cmd)`, returning
     * [returns] (null ⇒ void). Deliberately **not** variadic — see the class KDoc.
     */
    private fun msgSendHandle(argCount: Int, returns: ValueLayout?): MethodHandle? {
        val args = Array(argCount + 2) { ptr }
        val descriptor =
            if (returns == null) FunctionDescriptor.ofVoid(*args)
            else FunctionDescriptor.of(returns, *args)
        return objcHandle("objc_msgSend", descriptor)
    }

    // ── Classes, selectors, calls ─────────────────────────────────────────────────

    /** The `Class` named [name], or a null segment if the runtime doesn't know it. */
    fun cls(name: String): MemorySegment = withCString(name) { c ->
        val handle = objcHandle("objc_getClass", FunctionDescriptor.of(ptr, ptr))
            ?: return@withCString MemorySegment.NULL
        handle.invokeExact(c) as MemorySegment
    }

    /** The selector named [name] — `sel_registerName` interns, so this is idempotent. */
    fun sel(name: String): MemorySegment = withCString(name) { c ->
        val handle = objcHandle("sel_registerName", FunctionDescriptor.of(ptr, ptr))
            ?: return@withCString MemorySegment.NULL
        handle.invokeExact(c) as MemorySegment
    }

    /** Send [selector] to [receiver], returning the pointer result (`id`, `Class`, …). */
    fun send(receiver: MemorySegment, selector: String, vararg args: MemorySegment): MemorySegment {
        val handle = msgSendHandle(args.size, ptr) ?: return MemorySegment.NULL
        val sel = sel(selector)
        return invoke(handle, receiver, sel, args) as MemorySegment? ?: MemorySegment.NULL
    }

    /** Send [selector] for its side effect only. */
    fun sendVoid(receiver: MemorySegment, selector: String, vararg args: MemorySegment) {
        val handle = msgSendHandle(args.size, null) ?: return
        invoke(handle, receiver, sel(selector), args)
    }

    /** Send [selector] and read a `NSInteger`/`long` result. */
    fun sendLong(receiver: MemorySegment, selector: String, vararg args: MemorySegment): Long {
        val handle = msgSendHandle(args.size, i64) ?: return 0L
        return invoke(handle, receiver, sel(selector), args) as Long? ?: 0L
    }

    /** Send [selector] and read a `BOOL` result. */
    fun sendBool(receiver: MemorySegment, selector: String, vararg args: MemorySegment): Boolean {
        val handle = msgSendHandle(args.size, i8) ?: return false
        return (invoke(handle, receiver, sel(selector), args) as Byte?)?.toInt() != 0
    }

    /**
     * Send [selector] with a single `BOOL` argument.
     *
     * Worth its own entry point rather than smuggling the value in as a pointer: passing an
     * integer where the ABI expects a pointer-sized register happens to work on arm64 and
     * x86_64 because the callee reads the low bits, but it is coincidence, not contract. A
     * correctly-typed descriptor costs one function and removes the question.
     */
    fun sendVoidWithBool(receiver: MemorySegment, selector: String, value: Boolean) {
        val handle = objcHandle("objc_msgSend", FunctionDescriptor.ofVoid(ptr, ptr, i8))
            ?: return
        handle.invokeWithArguments(
            listOf(receiver, sel(selector), if (value) 1.toByte() else 0.toByte()),
        )
    }

    /**
     * Send [selector] with a single `NSInteger` argument, returning the pointer result —
     * `objectAtIndex:` and friends.
     */
    fun sendWithLong(receiver: MemorySegment, selector: String, value: Long): MemorySegment {
        val handle = objcHandle("objc_msgSend", FunctionDescriptor.of(ptr, ptr, ptr, i64))
            ?: return MemorySegment.NULL
        return handle.invokeWithArguments(listOf(receiver, sel(selector), value))
            as MemorySegment? ?: MemorySegment.NULL
    }

    /** Send [selector] with a single 32-bit `int` argument, returning the pointer result. */
    fun sendWithInt(receiver: MemorySegment, selector: String, value: Int): MemorySegment {
        val handle = objcHandle(
            "objc_msgSend", FunctionDescriptor.of(ptr, ptr, ptr, ValueLayout.JAVA_INT),
        ) ?: return MemorySegment.NULL
        return handle.invokeWithArguments(listOf(receiver, sel(selector), value))
            as MemorySegment? ?: MemorySegment.NULL
    }

    /** Send [selector] with an integer followed by pointer arguments, returning a pointer. */
    fun sendWithLongAndPointers(
        receiver: MemorySegment,
        selector: String,
        value: Long,
        vararg args: MemorySegment,
    ): MemorySegment {
        val handle = objcHandle(
            "objc_msgSend",
            FunctionDescriptor.of(ptr, ptr, ptr, i64, *Array(args.size) { ptr }),
        ) ?: return MemorySegment.NULL
        return handle.invokeWithArguments(listOf(receiver, sel(selector), value) + args.toList())
            as MemorySegment? ?: MemorySegment.NULL
    }

    /** Send [selector] with a pointer, an integer, and more pointer arguments, returning a pointer. */
    fun sendWithPointerLongAndPointers(
        receiver: MemorySegment,
        selector: String,
        pointer: MemorySegment,
        value: Long,
        vararg args: MemorySegment,
    ): MemorySegment {
        val handle = objcHandle(
            "objc_msgSend",
            FunctionDescriptor.of(ptr, ptr, ptr, ptr, i64, *Array(args.size) { ptr }),
        ) ?: return MemorySegment.NULL
        return handle.invokeWithArguments(
            listOf(receiver, sel(selector), pointer, value) + args.toList(),
        ) as MemorySegment? ?: MemorySegment.NULL
    }

    /** Send [selector] with a pointer and an integer, returning a pointer. */
    fun sendWithPointerAndLong(
        receiver: MemorySegment,
        selector: String,
        pointer: MemorySegment,
        value: Long,
    ): MemorySegment {
        val handle = objcHandle(
            "objc_msgSend", FunctionDescriptor.of(ptr, ptr, ptr, ptr, i64),
        ) ?: return MemorySegment.NULL
        return handle.invokeWithArguments(listOf(receiver, sel(selector), pointer, value))
            as MemorySegment? ?: MemorySegment.NULL
    }

    /**
     * Bind `objc_msgSend` for a signature the typed helpers above don't cover — anything
     * with a **by-value struct** in it, which on our side means every CoreMedia `CMTime`
     * (`seekToTime:`, `copyPixelBufferForItemTime:…`). [descriptor] must already carry
     * `(self, _cmd)` as its first two pointer parameters.
     *
     * Struct *arguments* need nothing special: FFM classifies them per the platform ABI,
     * and neither AAPCS64 (>16 bytes ⇒ passed by reference) nor SysV changes where `self`
     * and `_cmd` land. Struct *returns* do — use [msgSendReturningStruct].
     */
    fun msgSend(descriptor: FunctionDescriptor): MethodHandle? =
        objcHandle("objc_msgSend", descriptor)

    /**
     * Bind the right `objc_msgSend` variant for a method that **returns a struct by value**.
     *
     * ⚠⚠ **This is an architecture split, not a preference.** A struct too large for
     * registers is returned through a caller-provided hidden pointer, and the two ABIs put
     * that pointer in different places:
     *
     *  - **arm64** passes it in `x8`, which is outside the argument registers — so `self`
     *    and `_cmd` stay in `x0`/`x1` and plain `objc_msgSend` forwards it untouched.
     *    `objc_msgSend_stret` does not exist on arm64 and is declared unavailable there.
     *  - **x86_64** passes it as a hidden *first argument* in `rdi` — the register `self`
     *    would otherwise occupy. Calling plain `objc_msgSend` there shifts every argument
     *    by one and the receiver is read as the result pointer, so the runtime exports a
     *    separate `objc_msgSend_stret` whose signature accounts for the shift.
     *
     * FFM classifies the struct correctly for both, but it cannot know that the *symbol*
     * has to change with it — that part is Objective-C's, and it is why this is a function
     * rather than a descriptor the caller passes to [msgSend]. Getting it wrong reads the
     * receiver as a pointer and corrupts memory rather than failing, which is precisely the
     * class of bug this bridge's KDoc exists to prevent.
     *
     * Returns `null` on an architecture we have no rule for, so the caller degrades the way
     * every other entry point here does.
     */
    fun msgSendReturningStruct(descriptor: FunctionDescriptor): MethodHandle? {
        val arch = System.getProperty("os.arch").orEmpty()
        val symbol = when {
            arch.equals("aarch64", ignoreCase = true) || arch.equals("arm64", ignoreCase = true) ->
                "objc_msgSend"

            arch.equals("x86_64", ignoreCase = true) || arch.equals("amd64", ignoreCase = true) ->
                "objc_msgSend_stret"

            else -> return null
        }
        return objcHandle(symbol, descriptor)
    }

    /**
     * `invokeExact` demands a statically-exact call shape, which a `vararg` bridge can't
     * offer — so calls go through `invokeWithArguments`. It costs a boxing hop per call;
     * irrelevant for session setup, and the per-frame path (P2+) should bind its own exact
     * handle rather than route through here.
     */
    private fun invoke(
        handle: MethodHandle,
        receiver: MemorySegment,
        selector: MemorySegment,
        args: Array<out MemorySegment>,
    ): Any? = handle.invokeWithArguments(listOf(receiver, selector) + args.toList())

    // ── Memory + strings ──────────────────────────────────────────────────────────

    /** True for `nil` / a failed lookup. */
    fun isNull(segment: MemorySegment): Boolean = segment.equals(MemorySegment.NULL)

    /**
     * Give a returned pointer an unbounded size so it can be read through. FFM has no way
     * to know a `void*`'s extent; this asserts we do. Only use it on pointers whose target
     * the ObjC contract guarantees.
     */
    fun reinterpretUnbounded(segment: MemorySegment): MemorySegment =
        segment.reinterpret(Long.MAX_VALUE)

    private fun <T> withCString(text: String, block: (MemorySegment) -> T): T =
        Arena.ofConfined().use { arena -> block(arena.allocateFrom(text)) }

    /**
     * An **autoreleased** `NSString` for [text]. Valid until the enclosing
     * [autoreleasePool] drains, so wrap uses that outlive the immediate call.
     */
    fun nsString(text: String): MemorySegment = withCString(text) { c ->
        send(cls("NSString"), "stringWithUTF8String:", c)
    }

    /** Read an `NSString` back into a Kotlin string; empty for `nil`. */
    fun kotlinString(nsString: MemorySegment): String {
        if (isNull(nsString)) return ""
        val utf8 = send(nsString, "UTF8String")
        if (isNull(utf8)) return ""
        return reinterpretUnbounded(utf8).getString(0)
    }

    /**
     * Run [block] inside an `NSAutoreleasePool`, draining it afterwards. Framework getters
     * that return autoreleased objects (`+defaultDeviceWithMediaType:` and friends) leak
     * without one, since the JVM has no run loop to drain the thread's implicit pool.
     */
    fun <T> autoreleasePool(block: () -> T): T {
        val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
        return try {
            block()
        } finally {
            if (!isNull(pool)) sendVoid(pool, "drain")
        }
    }

    /** `alloc` + `init` on [className]; caller owns the +1 reference (see [release]). */
    fun allocInit(className: String): MemorySegment = send(send(cls(className), "alloc"), "init")

    /** Balance an `alloc`/`copy`/`retain`. No-op on nil. */
    fun release(obj: MemorySegment) {
        if (!isNull(obj)) sendVoid(obj, "release")
    }

    // ── Framework constants ───────────────────────────────────────────────────────

    private val frameworks = ConcurrentHashMap<String, SymbolLookup>()

    /**
     * Load a system framework by name (`"AVFoundation"`, `"CoreMedia"`, …). Loading alone
     * triggers no permission prompt — only *using* a capability does.
     */
    fun framework(name: String): SymbolLookup? {
        if (!isAvailable) return null
        return frameworks.getOrPut(name) {
            runCatching {
                SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/$name.framework/$name", global,
                )
            }.getOrNull() ?: return null
        }
    }

    /**
     * Read a pointer-valued constant exported by a framework — e.g. `AVMediaTypeVideo`,
     * which is an `NSString *` the API compares by identity, so it must be *read*, not
     * reconstructed from its text.
     */
    fun frameworkConstant(frameworkName: String, symbol: String): MemorySegment {
        val lookup = framework(frameworkName) ?: return MemorySegment.NULL
        val address = lookup.find(symbol).orElse(null) ?: return MemorySegment.NULL
        return address.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ptr, 0)
    }

    // ── Blocks ────────────────────────────────────────────────────────────────────

    /**
     * Build a **global** Objective-C block whose body is [target].
     *
     * Blocks are not a language feature the C ABI exposes — they're a struct with a known
     * layout, so we lay one out by hand. On 64-bit that is `isa` (8) + `flags` (4) +
     * `reserved` (4) + `invoke` (8) + `descriptor` (8) = 32 bytes, with `isa` pointing at
     * `_NSConcreteGlobalBlock` and `flags` carrying `BLOCK_IS_GLOBAL`.
     *
     * **Global** is the right kind here: a global block has no captured state and no
     * lifetime — no copy/dispose helpers, no reference counting for the runtime to get
     * wrong. State the body needs is captured on the *Kotlin* side by the lambda instead,
     * which is both simpler and safer than teaching the runtime about a JVM object.
     *
     * [invokeDescriptor] describes the block's `invoke` function, whose **first parameter
     * is the block pointer itself**, followed by the block's declared arguments. The block
     * and its upcall stub live in the process-global arena — a framework may retain a block
     * past the call that received it, and an arena closing underneath it would be a crash
     * with no useful stack.
     */
    fun globalBlock(invokeDescriptor: FunctionDescriptor, target: MethodHandle): MemorySegment {
        if (!isAvailable) return MemorySegment.NULL
        val isa = linker!!.defaultLookup().find("_NSConcreteGlobalBlock").orElse(null)
            ?: return MemorySegment.NULL
        val invoke = linker.upcallStub(target, invokeDescriptor, global)

        // struct Block_descriptor_1 { unsigned long reserved; unsigned long size; }
        val descriptor = global.allocate(16)
        descriptor.set(i64, 0, 0L)
        descriptor.set(i64, 8, BLOCK_SIZE)

        val literal = global.allocate(BLOCK_SIZE)
        literal.set(ptr, 0, isa)
        literal.set(ValueLayout.JAVA_INT, 8, BLOCK_IS_GLOBAL)
        literal.set(ValueLayout.JAVA_INT, 12, 0)
        literal.set(ptr, 16, invoke)
        literal.set(ptr, 24, descriptor)
        return literal
    }

    /** A block body taking a single `BOOL` — the shape of most completion handlers. */
    fun interface BoolBlock {
        fun invoke(block: MemorySegment, value: Byte)
    }

    /**
     * A `void (^)(BOOL)` block — e.g. `requestAccessForMediaType:completionHandler:`.
     * The completion fires on an internal queue, so [body] must be thread-safe.
     */
    fun boolBlock(body: BoolBlock): MemorySegment {
        if (!isAvailable) return MemorySegment.NULL
        val handle = MethodHandles.lookup()
            .findVirtual(
                BoolBlock::class.java, "invoke",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, Byte::class.java),
            )
            .bindTo(body)
        return globalBlock(FunctionDescriptor.ofVoid(ptr, i8), handle)
    }

    /** A block body taking `(BOOL, NSError *)` — the reply shape of `LAContext.evaluatePolicy`. */
    fun interface BoolErrorBlock {
        fun invoke(block: MemorySegment, value: Byte, error: MemorySegment)
    }

    /**
     * A `void (^)(BOOL, NSError *)` block. Like [boolBlock] it fires on a framework-private
     * queue, so [body] must be thread-safe — and must not throw: an exception escaping an
     * upcall takes the whole JVM down.
     */
    fun boolErrorBlock(body: BoolErrorBlock): MemorySegment {
        if (!isAvailable) return MemorySegment.NULL
        val handle = MethodHandles.lookup()
            .findVirtual(
                BoolErrorBlock::class.java, "invoke",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Byte::class.java, MemorySegment::class.java,
                ),
            )
            .bindTo(body)
        return globalBlock(FunctionDescriptor.ofVoid(ptr, i8, ptr), handle)
    }

    /** 64-bit block literal size; see [globalBlock]. */
    private const val BLOCK_SIZE = 32L

    /** `BLOCK_IS_GLOBAL` — the block is static, so the runtime never copies or frees it. */
    private const val BLOCK_IS_GLOBAL = 1 shl 28

    // ── Runtime-registered classes (delegates) ────────────────────────────────────

    /** An ObjC method body taking only `(self, _cmd)`. */
    fun interface Method0 {
        fun invoke(self: MemorySegment, cmd: MemorySegment)
    }

    /**
     * An ObjC method body taking three object arguments after `(self, _cmd)` — the shape of
     * `captureOutput:didOutputSampleBuffer:fromConnection:`, which is why this arity exists.
     */
    fun interface Method3 {
        fun invoke(
            self: MemorySegment,
            cmd: MemorySegment,
            a1: MemorySegment,
            a2: MemorySegment,
            a3: MemorySegment,
        )
    }

    private val registered = ConcurrentHashMap<String, MemorySegment>()

    /**
     * Define an ObjC class named [name] (subclassing [superclassName]) with method bodies
     * implemented in Kotlin, and return the `Class`.
     *
     * **Registration is process-global and happens once per [name]** — the runtime rejects a
     * second `objc_allocateClassPair` for the same name, and the upcall stubs must outlive
     * every call the framework makes into them. So repeat calls return the cached class and
     * *ignore* the passed bodies; a per-instance callback target belongs in the delegate's
     * associated state, not in a fresh class per scan.
     *
     * [types] is the ObjC type encoding of each method, e.g. `"v@:@@@"` = returns void,
     * takes self + selector + three objects.
     */
    fun registerClass(
        name: String,
        superclassName: String,
        methods0: Map<String, Pair<String, Method0>> = emptyMap(),
        methods3: Map<String, Pair<String, Method3>> = emptyMap(),
    ): MemorySegment {
        registered[name]?.let { return it }
        if (!isAvailable) return MemorySegment.NULL

        val superclass = cls(superclassName)
        if (isNull(superclass)) return MemorySegment.NULL

        val created = withCString(name) { c ->
            val handle = objcHandle(
                "objc_allocateClassPair",
                FunctionDescriptor.of(ptr, ptr, ptr, ValueLayout.JAVA_LONG),
            ) ?: return@withCString MemorySegment.NULL
            handle.invokeExact(superclass, c, 0L) as MemorySegment
        }
        if (isNull(created)) return MemorySegment.NULL

        methods0.forEach { (selector, spec) ->
            addMethod(created, selector, spec.first, upcall(spec.second))
        }
        methods3.forEach { (selector, spec) ->
            addMethod(created, selector, spec.first, upcall(spec.second))
        }

        objcHandle("objc_registerClassPair", FunctionDescriptor.ofVoid(ptr))
            ?.let { handle -> handle.invokeExact(created) as Unit }

        registered[name] = created
        return created
    }

    private fun addMethod(
        target: MemorySegment,
        selector: String,
        types: String,
        imp: MemorySegment,
    ) {
        withCString(types) { encoded ->
            val handle = objcHandle(
                "class_addMethod",
                FunctionDescriptor.of(i8, ptr, ptr, ptr, ptr),
            ) ?: return@withCString
            // ⚠ `invokeExact` is signature-polymorphic: its call-site type comes from the
            // static shape of the call, so the result MUST be cast to the descriptor's
            // return type (and must not sit behind a `?.`, which boxes it) or the JVM
            // throws WrongMethodTypeException at the first invocation.
            handle.invokeExact(target, sel(selector), imp, encoded) as Byte
        }
    }

    private fun upcall(body: Method0): MemorySegment {
        val handle = MethodHandles.lookup()
            .findVirtual(
                Method0::class.java, "invoke",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                ),
            )
            .bindTo(body)
        return linker!!.upcallStub(handle, FunctionDescriptor.ofVoid(ptr, ptr), global)
    }

    private fun upcall(body: Method3): MemorySegment {
        val handle = MethodHandles.lookup()
            .findVirtual(
                Method3::class.java, "invoke",
                MethodType.methodType(
                    Void.TYPE,
                    MemorySegment::class.java, MemorySegment::class.java,
                    MemorySegment::class.java, MemorySegment::class.java,
                    MemorySegment::class.java,
                ),
            )
            .bindTo(body)
        return linker!!.upcallStub(
            handle, FunctionDescriptor.ofVoid(ptr, ptr, ptr, ptr, ptr), global,
        )
    }
}
