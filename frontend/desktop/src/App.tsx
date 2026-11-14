import { useState } from "react";
import "./App.css";

function App() {
  const [isFocused, setIsFocused] = useState(false);

  return (
    <main className="desktop-shell">
      <header className="topbar" aria-label="NexusAI desktop">
        <div className="wordmark">
          <span className="wordmark-indicator" aria-hidden="true" />
          <span>NEXUS</span>
        </div>
        <span className="topbar-label">PERSONAL ASSISTANT</span>
      </header>

      <section className="assistant-stage" aria-labelledby="assistant-name">
        <p className="stage-eyebrow">INTELLIGENCE IN MOTION</p>

        <button
          className={`orbital-control${isFocused ? " is-focused" : ""}`}
          type="button"
          aria-label={isFocused ? "Return display to standby" : "Focus the display"}
          aria-pressed={isFocused}
          onClick={() => setIsFocused((focused) => !focused)}
        >
          <span className="orbit orbit-outer" aria-hidden="true">
            <span className="orbit-marker" />
          </span>
          <span className="orbit orbit-middle" aria-hidden="true">
            <span className="orbit-marker" />
          </span>
          <span className="orbit orbit-inner" aria-hidden="true">
            <span className="orbit-marker" />
          </span>
          <span className="control-core" aria-hidden="true">
            <span className="core-point" />
          </span>
        </button>

        <h1 id="assistant-name">NEXUS</h1>
        <p className="assistant-status" role="status">
          {isFocused ? "FOCUSED" : "STANDBY"}
        </p>
        <p className="stage-hint">Select the center control to change its visual state</p>
      </section>

      <footer className="statusbar">
        <span>INTERFACE PREVIEW</span>
        <span>DESKTOP&nbsp; / &nbsp;01</span>
      </footer>
    </main>
  );
}

export default App;
