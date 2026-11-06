/** True when this browser can give MapLibre a WebGL context. Checked BEFORE MapLibre is even loaded. */
export function supportsWebGL(doc: Document = document): boolean {
  try {
    const canvas = doc.createElement('canvas')
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'))
  } catch {
    return false
  }
}
