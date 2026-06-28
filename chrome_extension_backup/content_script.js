// Isolated World Content Script - GazeReader Bridge Proxy
// Coordinates message passing between webpage DOM and extension runtime.


// --- 1. Bridge: Webpage (Main World) -> Extension (Side Panel/Background) ---
window.addEventListener("message", (event) => {
  // Security check: Only allow messages from our own window origin
  if (event.source !== window) return;

  const data = event.data;
  if (!data || data.source !== "GAZE_MAIN_WORLD") return;

  // Forward signals to extension Side Panel or Background
  if (data.type === "calibration_clicks_update") {
    chrome.runtime.sendMessage({ type: "page_calibration_clicks_update", total: data.total }, () => {
      if (chrome.runtime.lastError) { /* ignore closed side panel */ }
    });
  } else if (data.type === "calibration_complete") {
    chrome.runtime.sendMessage({ type: "page_calibration_complete" }, () => {
      if (chrome.runtime.lastError) { /* ignore */ }
    });
  } else if (data.type === "heg_status_update") {
    chrome.runtime.sendMessage({ type: "page_heg_status_update", status: data.status, colorClass: data.colorClass }, () => {
      if (chrome.runtime.lastError) { /* ignore */ }
    });
  } else if (data.type === "user_interaction_ping") {
    chrome.runtime.sendMessage({ type: "reset_distraction_timer" }, () => {
      if (chrome.runtime.lastError) { /* ignore */ }
    });
  }
});

// --- 2. Bridge: Extension (Side Panel/Background) -> Webpage (Main World) ---
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // Forward all messages directly to Main World (main_world.js)
  window.postMessage({
    source: "GAZE_EXTENSION",
    type: request.type,
    data: request
  }, "*");

  sendResponse({ success: true });
  return true;
});
