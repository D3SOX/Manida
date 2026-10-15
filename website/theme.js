(() => {
  "use strict";
  const root = document.documentElement;
  const modeButton = document.querySelector(".mode-button");
  let dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  let followsSystem = true;
  try {
    const mode = localStorage.getItem("manida-mode");
    if (mode === "dark" || mode === "light") { dark = mode === "dark"; followsSystem = false; }
  } catch { /* Theme switching also works when storage is unavailable. */ }
  function apply() {
    root.dataset.mode = dark ? "dark" : "light";
    modeButton.setAttribute("aria-label", `Switch to ${dark ? "light" : "dark"} theme`);
    modeButton.setAttribute("aria-pressed", String(dark));
    document.querySelector('meta[name="theme-color"]').content = dark ? "#141218" : "#fffbfe";
    // Keep the transparent QR readable when the user overrides the system theme.
    const qr = document.querySelector(".qr picture");
    const qrSource = dark ? "assets/fdroid-qr-dark.png" : "assets/fdroid-qr-light.png";
    qr.querySelector("source").srcset = qrSource;
    qr.querySelector("img").src = qrSource;
  }
  modeButton.addEventListener("click", () => {
    dark = !dark; followsSystem = false; apply();
    try { localStorage.setItem("manida-mode", dark ? "dark" : "light"); } catch { /* Optional preference. */ }
  });
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", event => {
    if (followsSystem) { dark = event.matches; apply(); }
  });
  apply();

  const copyIcon = '<svg class="icon copy-icon" aria-hidden="true" focusable="false"><use href="assets/icons.svg#content_copy"/></svg>';
  const checkIcon = '<svg class="icon check-icon" aria-hidden="true" focusable="false"><use href="assets/icons.svg#check"/></svg>';
  const status = document.createElement("span");
  status.className = "sr-only";
  status.setAttribute("role", "status");
  document.body.append(status);
  document.querySelectorAll(".code-block").forEach(block => {
    const button = document.createElement("button");
    const label = block.dataset.copyLabel;
    button.type = "button";
    button.className = "copy-button";
    button.innerHTML = copyIcon + checkIcon;
    button.setAttribute("aria-label", label);
    button.title = label;
    let reset;
    button.addEventListener("click", async () => {
      clearTimeout(reset);
      try {
        await navigator.clipboard.writeText(block.querySelector("code").textContent);
        button.dataset.copied = "true";
        button.setAttribute("aria-label", "Copied");
        button.title = "Copied";
        status.textContent = "Copied to clipboard";
      } catch {
        delete button.dataset.copied;
        button.setAttribute("aria-label", label);
        status.textContent = "Could not copy. Select the code and copy it manually.";
        button.title = status.textContent;
      }
      reset = setTimeout(() => {
        delete button.dataset.copied;
        button.setAttribute("aria-label", label);
        button.title = label;
        status.textContent = "";
      }, 2_000);
    });
    block.append(button);
  });
})();
