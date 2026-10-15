(() => {
  "use strict";
  const palettes = {
    purple: ["#6750a4", "#eaddff", "#21005d", "#d0bcff", "#381e72", "#4f378b"],
    blue: ["#315da8", "#d8e2ff", "#001a42", "#afc6ff", "#002e69", "#174580"],
    teal: ["#006a60", "#9ef2e3", "#00201c", "#82d5c7", "#003730", "#005047"],
    green: ["#416832", "#c2eeb1", "#062100", "#a7d397", "#14380a", "#2b5020"],
    orange: ["#8b5000", "#ffdcbb", "#2c1600", "#ffb866", "#4a2800", "#693c00"],
    pink: ["#984061", "#ffd9e2", "#3f001b", "#ffb1c8", "#5e1131", "#7b2948"],
  };
  const root = document.documentElement;
  const modeButton = document.querySelector(".mode-button");
  const buttons = document.querySelectorAll(".palette button");
  let color = "purple";
  let dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  let followsSystem = true;
  try {
    const saved = localStorage.getItem("manida-color");
    if (Object.hasOwn(palettes, saved)) color = saved;
    const mode = localStorage.getItem("manida-mode");
    if (mode === "dark" || mode === "light") { dark = mode === "dark"; followsSystem = false; }
  } catch { /* The color selector also works when storage is unavailable. */ }
  function apply() {
    const palette = palettes[color];
    const tokens = {
      primary: dark ? palette[3] : palette[0],
      "on-primary": dark ? palette[4] : "#ffffff",
      "primary-container": dark ? palette[5] : palette[1],
      "on-primary-container": dark ? palette[1] : palette[2],
      surface: dark ? "#141218" : "#fffbfe",
      "surface-container": dark ? "#211f26" : "#f3edf7",
      "surface-high": dark ? "#2b2930" : "#ece6f0",
      "on-surface": dark ? "#e6e0e9" : "#1d1b20",
      "on-surface-variant": dark ? "#cac4d0" : "#49454f",
      outline: dark ? "#938f99" : "#79747e",
      "outline-variant": dark ? "#49454f" : "#cac4d0",
    };
    Object.entries(tokens).forEach(([key, value]) => root.style.setProperty(`--${key}`, value));
    root.style.colorScheme = dark ? "dark" : "light";
    root.dataset.color = color;
    root.dataset.mode = dark ? "dark" : "light";
    modeButton.setAttribute("aria-label", `Switch to ${dark ? "light" : "dark"} theme`);
    modeButton.setAttribute("aria-pressed", String(dark));
    buttons.forEach(button => button.setAttribute("aria-pressed", String(button.dataset.color === color)));
    document.querySelector('meta[name="theme-color"]').content = tokens.surface;
    // Keep the transparent QR readable when the user overrides the system theme.
    const qr = document.querySelector(".qr picture");
    const qrSource = dark ? "assets/fdroid-qr-dark.png" : "assets/fdroid-qr-light.png";
    qr.querySelector("source").srcset = qrSource;
    qr.querySelector("img").src = qrSource;
  }
  buttons.forEach(button => button.addEventListener("click", () => {
    color = button.dataset.color;
    apply();
    try { localStorage.setItem("manida-color", color); } catch { /* Optional preference. */ }
    document.querySelector(".color-picker").open = false;
  }));
  modeButton.addEventListener("click", () => {
    dark = !dark; followsSystem = false; apply();
    try { localStorage.setItem("manida-mode", dark ? "dark" : "light"); } catch { /* Optional preference. */ }
  });
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", event => {
    if (followsSystem) { dark = event.matches; apply(); }
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") document.querySelector(".color-picker").open = false;
  });
  apply();
})();
