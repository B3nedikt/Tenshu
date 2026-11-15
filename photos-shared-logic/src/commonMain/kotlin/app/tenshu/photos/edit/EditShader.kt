package app.tenshu.photos.edit

/**
 * The Adjust + Filter shader, **written once** and handed to each engine in its own language
 * (`photos-editing.md` ED-D8): AGSL on Android 33+ (the on-screen preview only — a bitmap canvas
 * there cannot run it, so Android saves through [EditColor], §10.4), SkSL on desktop — the same text,
 * the two are the same language — and the Core Image Kernel Language on iOS, translated
 * mechanically from it.
 *
 * ⚠⚠ [EditColor] is the same math in Kotlin, line for line. It is the reference the golden tests pin
 * every engine to ([EditParityFixtures]), and it is what every Android save runs (§10.4). **Change one, change the other in the same commit** — the parity tests fail otherwise.
 *
 * ⚠ Every value here is **sRGB-encoded**, not linear: Skia and Android hand a runtime shader the
 * destination's encoded values, and the Core Image pipeline converts to the same encoding around the
 * kernel (`EditColorPipeline` in the iOS SDK). The sliders were tuned in that space.
 *
 * ### Uniforms
 * - `u0` = brightness, contrast, tone, white point; `u1` = highlights, shadows, black point,
 *   saturation; `u2` = warmth, tint, vignette, 0 — each the slider's value / 100 ([EditColor.uniforms]).
 * - `v` = the vignette's centre (x, y) in output pixels and 1 / its half-size (x, y)
 *   ([EditColor.vignetteUniforms]).
 * - `f` = the LUT's lattice size and the look's intensity `0..1` (Skia only; Core Image draws the LUT
 *   with `CIColorCube`, `EditColor.cubeData`).
 */
object EditShader {

    /**
     * The adjust math, as one function of a pixel and its place. The order inside is fixed: levels
     * (black and white point), brightness, contrast, tone, highlights and shadows, saturation,
     * warmth and tint, vignette.
     */
    val ADJUST_BODY: String = """
float3 tenshuAdjust(float3 c, float2 p, float4 u0, float4 u1, float4 u2) {
    float lo = 0.2 * u1.z;
    float hi = 1.0 - 0.2 * u0.w;
    c = clamp((c - lo) / (hi - lo), 0.0, 1.0);
    c = pow(c, float3(exp2(-u0.x)));
    c = clamp((c - 0.5) * exp2(u0.y) + 0.5, 0.0, 1.0);
    c = c + (c * c * (3.0 - 2.0 * c) - c) * u0.z;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = c + 0.25 * (u1.x * smoothstep(0.5, 1.0, l) + u1.y * (1.0 - smoothstep(0.0, 0.5, l)));
    c = clamp(c, 0.0, 1.0);
    l = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = clamp(float3(l) + (c - float3(l)) * (1.0 + u1.w), 0.0, 1.0);
    c = clamp(c + float3(0.08 * u2.x, -0.08 * u2.y, -0.08 * u2.x), 0.0, 1.0);
    float m = smoothstep(0.35, 1.0, length(p) * 0.70710678) * abs(u2.z);
    c = mix(c * (1.0 - 0.85 * m), c + (1.0 - c) * (0.6 * m), step(0.0, u2.z));
    return clamp(c, 0.0, 1.0);
}
""".trimStart()

    /**
     * The whole pass for Skia (desktop) and AGSL (Android 33+): the transformed picture in, adjust,
     * then the look's LUT at its intensity — [EditRecipe]'s order.
     *
     * ⚠ The LUT is a `(n·n) × n` image sampled at texel centres with no filtering (Android's
     * `setInputBuffer`), and the trilinear blend is done here, eight texels by hand — so the
     * interpolation is ours on both Skia engines rather than each GPU's. Core Image's `CIColorCube`
     * interpolates the same 8-bit lattice itself (pinned by the iOS parity test).
     */
    val SKSL: String = """
uniform shader image;
uniform shader lut;
uniform float4 u0;
uniform float4 u1;
uniform float4 u2;
uniform float4 v;
uniform float4 f;

$ADJUST_BODY
float3 tenshuLutTexel(float r, float g, float b) {
    return float3(lut.eval(float2(b * f.x + r + 0.5, g + 0.5)).rgb);
}

float3 tenshuLookup(float3 c) {
    float m = f.x - 1.0;
    float3 x = clamp(c, 0.0, 1.0) * m;
    float3 i = min(floor(x), float3(m - 1.0));
    float3 t = x - i;
    float3 c00 = mix(tenshuLutTexel(i.x, i.y, i.z), tenshuLutTexel(i.x + 1.0, i.y, i.z), t.x);
    float3 c10 = mix(tenshuLutTexel(i.x, i.y + 1.0, i.z), tenshuLutTexel(i.x + 1.0, i.y + 1.0, i.z), t.x);
    float3 c01 = mix(tenshuLutTexel(i.x, i.y, i.z + 1.0), tenshuLutTexel(i.x + 1.0, i.y, i.z + 1.0), t.x);
    float3 c11 = mix(tenshuLutTexel(i.x, i.y + 1.0, i.z + 1.0), tenshuLutTexel(i.x + 1.0, i.y + 1.0, i.z + 1.0), t.x);
    return mix(mix(c00, c10, t.y), mix(c01, c11, t.y), t.z);
}

half4 main(float2 coord) {
    half4 s = image.eval(coord);
    float a = float(s.a);
    if (a <= 0.0) {
        return half4(0.0);
    }
    float3 c = float3(s.rgb) / a;
    c = tenshuAdjust(c, (coord - v.xy) * v.zw, u0, u1, u2);
    if (f.y > 0.0) {
        c = mix(c, tenshuLookup(c), f.y);
    }
    return half4(half3(c * a), half(a));
}
"""

    /** AGSL is SkSL as far as this shader goes: one text, so the two cannot drift. */
    val AGSL: String get() = SKSL

    /**
     * The Core Image Kernel Language colour kernel: [ADJUST_BODY] with its vector types renamed to
     * GLSL's, around a `destCoord()` wrapper. Arguments: `u0, u1, u2, v` as above, in Core Image's
     * y-up output coordinates (the vignette is radial, so only its centre moves).
     *
     * ⚠ CIKL is deprecated since iOS 12 but still in the iOS 26 SDK, and it is the one Core Image
     * route that takes a *source string* — which is what lets the body be shared rather than ported
     * by hand into a build-time `.ci.metal` file. If Apple removes it, the body is what moves to Metal.
     */
    val CORE_IMAGE: String = toGlsl(ADJUST_BODY) + """
kernel vec4 tenshuAdjustKernel(__sample s, vec4 u0, vec4 u1, vec4 u2, vec4 v) {
    float a = max(s.a, 0.00001);
    vec3 c = s.rgb / a;
    c = tenshuAdjust(c, (destCoord() - v.xy) * v.zw, u0, u1, u2);
    return vec4(c * s.a, s.a);
}
"""

    /** SkSL's `float2/3/4` → GLSL's `vec2/3/4`, the only difference this body has between the two. */
    internal fun toGlsl(sksl: String): String = Regex("\\bfloat([234])\\b").replace(sksl) { "vec${it.groupValues[1]}" }
}
