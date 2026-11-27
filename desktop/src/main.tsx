import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/viewer.css";

const root = document.getElementById("root");
if (!root) throw new Error("#root element missing from index.html");

// One bundle, two windows: the viewer is opened at index.html#viewer. Each
// window loads only its own code, so a failure in one cannot break the other.
const isViewer = window.location.hash === "#viewer";
document.body.classList.toggle("is-viewer", isViewer);

const app = isViewer
  ? import("./viewer/ViewerApp").then((m) => <m.ViewerApp />)
  : import("./home/HomeApp").then((m) => <m.HomeApp />);

void app.then((element) => createRoot(root).render(<StrictMode>{element}</StrictMode>));
