# NexusAI Desktop

The first laptop application shell uses Tauri 2, React, TypeScript, and Vite.
The interface currently contains the NexusAI orbital control and a local visual
focus state. It does not access a microphone, AI service, or database.

## Run the desktop application

From this directory inside the `nexusai-dev` Toolbox:

```sh
npm install
npm run tauri dev
```

Tauri starts the Vite development server and opens a native desktop window. The
window loads the React page from Vite while developing.

## Build the frontend

```sh
npm run build
```

This runs TypeScript checking and creates the production web assets in `dist/`.
For a packaged desktop build, use `npm run tauri build`.

## How the pieces connect

- `src/` contains the React interface and its styles.
- Vite serves the interface during development and creates `dist/` for release.
- `src-tauri/` contains the Rust application entry point and Tauri configuration.
- Tauri creates the native window and displays the frontend in the system webview.
- React-to-Rust commands can be added later through Tauri's `invoke` API and
  Rust `#[tauri::command]` functions. The current interface does not call Rust.
