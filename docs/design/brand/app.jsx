// app.jsx — Brand Foundations: Tweaks panel only.
// The page content is static HTML for direct-edit fidelity. This file mounts
// a React island that owns the Tweaks shell and rewrites CSS variables on
// :root whenever the user picks a different palette / dark / mark.
//
// In the upstream design tool the panel activates on host postMessage
// (`__activate_edit_mode`); when foundations.html is opened standalone
// in a browser the message never arrives, so the panel stays closed and
// the page renders against TWEAK_DEFAULTS. The mark-mounting effect runs
// unconditionally on mount, so the cat marks render either way.

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "palette": "amethyst",
  "amethystShade": "canonical",
  "dark": false,
  "preferredMark": "outline"
}/*EDITMODE-END*/;

// Four Amethyst sub-variations. Override only --primary + --primary-soft +
// --on-primary; everything else inherits from the canonical Amethyst scheme.
const AMETHYST_SHADES = {
  canonical: { name: 'A · Canonical', light: { '--primary': '#5A4A96', '--primary-soft': '#E6DEFF', '--on-primary': '#FFFFFF' }, dark: { '--primary': '#CBC0FF', '--primary-soft': '#42347D', '--on-primary': '#2C1E71' } },
  deeper:    { name: 'B · Deeper',    light: { '--primary': '#4D3F8C', '--primary-soft': '#E1D8FA', '--on-primary': '#FFFFFF' }, dark: { '--primary': '#B8AAFF', '--primary-soft': '#352874', '--on-primary': '#1F1166' } },
  warmer:    { name: 'C · Warmer',    light: { '--primary': '#6E4F95', '--primary-soft': '#F1E2FF', '--on-primary': '#FFFFFF' }, dark: { '--primary': '#D9B8FF', '--primary-soft': '#4D3373', '--on-primary': '#2C1859' } },
  vault:     { name: 'D · Vault',     light: { '--primary': '#3E2F6F', '--primary-soft': '#DCD0F4', '--on-primary': '#FFFFFF' }, dark: { '--primary': '#A99CE8', '--primary-soft': '#26195C', '--on-primary': '#0F063D' } },
};

function applyPalette(name, dark, amethystShade) {
  const root = document.documentElement;
  const p = window.PALETTES[name];
  if (!p) return;
  const scheme = dark ? p.dark : p.light;
  for (const [k, v] of Object.entries(scheme)) root.style.setProperty(k, v);
  // If the active palette is amethyst, layer the shade override on top.
  if (name === 'amethyst' && amethystShade && AMETHYST_SHADES[amethystShade]) {
    const sh = AMETHYST_SHADES[amethystShade][dark ? 'dark' : 'light'];
    for (const [k, v] of Object.entries(sh)) root.style.setProperty(k, v);
  }
  root.dataset.palette = name;
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.amethystShade = amethystShade || '';
  // Notify the static swatch renderers in index.html
  window.dispatchEvent(new CustomEvent('tweakchange'));
}

function applyPreferredMark(name) {
  document.documentElement.dataset.mark = name;
}

function App() {
  const [t, setTweak] = window.useTweaks(TWEAK_DEFAULTS);

  React.useEffect(() => { applyPalette(t.palette, t.dark, t.amethystShade); },
                  [t.palette, t.dark, t.amethystShade]);
  React.useEffect(() => { applyPreferredMark(t.preferredMark); }, [t.preferredMark]);

  // Mount the cat marks into static placeholders. React-render once.
  React.useEffect(() => {
    const M = window.TenshuMarks;
    document.querySelectorAll('[data-mark-slot]').forEach((el) => {
      const variant = el.dataset.markSlot;
      const size = Number(el.dataset.size || 64);
      const stroke = Number(el.dataset.stroke || 2);
      const Component = variant === 'solid' ? M.Solid : M.Outline;
      const root = ReactDOM.createRoot(el);
      root.render(<Component size={size} stroke={stroke} />);
    });
  }, []);

  const palettes = Object.keys(window.PALETTES);
  const showAmethystShades = t.palette === 'amethyst';

  return (
    <window.TweaksPanel title="Tenshu — Brand">
      <window.TweakSection label="Color">
        <window.TweakRadio
          label="Palette" value={t.palette}
          options={palettes.map(p => ({ value: p, label: window.PALETTES[p].name }))}
          onChange={(v) => setTweak('palette', v)} />
        {showAmethystShades && (
          <window.TweakSelect
            label="Amethyst shade" value={t.amethystShade}
            options={Object.entries(AMETHYST_SHADES).map(([v, s]) => ({
              value: v, label: s.name,
            }))}
            onChange={(v) => setTweak('amethystShade', v)} />
        )}
        <window.TweakToggle label="Dark mode" value={t.dark}
          onChange={(v) => setTweak('dark', v)} />
      </window.TweakSection>
      <window.TweakSection label="Mark">
        <window.TweakRadio label="Variant" value={t.preferredMark}
          options={[
            { value: 'outline', label: 'Outline (primary)' },
            { value: 'solid', label: 'Solid' },
          ]}
          onChange={(v) => setTweak('preferredMark', v)} />
      </window.TweakSection>
    </window.TweaksPanel>
  );
}

const root = ReactDOM.createRoot(document.getElementById('tweaks-root'));
root.render(<App />);

// Bootstrap default palette before React paints, so the page never flashes
// its base black-on-white.
applyPalette(TWEAK_DEFAULTS.palette, TWEAK_DEFAULTS.dark, TWEAK_DEFAULTS.amethystShade);
applyPreferredMark(TWEAK_DEFAULTS.preferredMark);
