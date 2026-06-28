// Background Service Worker for GazeReader (MV3)
// Routes eye coordinates, tracks focus states, and handles tab blacklist checks.

let currentDistractionDomain = null;
let distractionStartTime = null;

// --- Configure Side Panel to Open on Action Icon Click ---
if (chrome.sidePanel && chrome.sidePanel.setPanelBehavior) {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true })
    .catch((error) => console.error("Error setting side panel behavior:", error));
}

// --- Coordinates Routing & Message Dispatcher ---
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // 1. Forward Gaze coordinates from Side Panel to Active Tab Content Script
  if (request.type === "gaze_coordinates_update") {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs && tabs[0] && tabs[0].id) {
        chrome.tabs.sendMessage(tabs[0].id, {
          type: "gaze_overlay_update",
          x: request.x,
          y: request.y,
          confidence: request.confidence
        }, () => {
          if (chrome.runtime.lastError) { /* ignore page unload warnings */ }
        });
      }
    });
    sendResponse({ success: true });
    return true;
  }

  // 2. Forward User Interaction Telemetry from Content Script to Side Panel
  if (request.type === "user_interaction_ping") {
    // Notify side panel to reset warning timers
    chrome.runtime.sendMessage({ type: "reset_distraction_timer", source: "user_activity" }, () => {
      if (chrome.runtime.lastError) { /* side panel might be closed */ }
    });
    sendResponse({ success: true });
    return true;
  }

  // 3. Start Session signaling from Side Panel
  if (request.type === "start_session") {
    chrome.storage.local.set({ studySessionActive: true }, () => {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs && tabs[0]) evaluateTabState(tabs[0]);
      });
      sendResponse({ success: true });
    });
    return true;
  }

  // 4. Stop Session signaling from Side Panel
  if (request.type === "stop_session") {
    chrome.storage.local.get("distractionLogs", (res) => {
      stopDistractionTiming(res.distractionLogs || {});
      chrome.storage.local.set({ studySessionActive: false }, () => {
        // Clear blocker overlays on active tabs
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
          if (tabs && tabs[0] && tabs[0].id) {
            chrome.tabs.sendMessage(tabs[0].id, { type: "session_reset" }, () => {
              if (chrome.runtime.lastError) { /* no-op */ }
            });
          }
        });
        sendResponse({ success: true });
      });
    });
    return true;
  }
});

// --- Tab Distraction Checking ---

// Track tab switches
chrome.tabs.onActivated.addListener((activeInfo) => {
  chrome.tabs.get(activeInfo.tabId, (tab) => {
    if (chrome.runtime.lastError || !tab) return;
    evaluateTabState(tab);
  });
});

// Track page navigations
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "complete" && tab.active) {
    evaluateTabState(tab);
  }
});

// Track when browser window loses focus (switched desktop window, lock screen, etc.)
chrome.windows.onFocusChanged.addListener((windowId) => {
  chrome.storage.local.get(["studySessionActive", "distractionLogs"], (res) => {
    if (!res.studySessionActive) return;
    const logs = res.distractionLogs || {};

    if (windowId === chrome.windows.WINDOW_ID_NONE) {
      if (!currentDistractionDomain) {
        currentDistractionDomain = "other_apps";
        distractionStartTime = Date.now();
        
        // Pause session
        notifyDistractionStart("Outside Browser");
      }
    } else {
      chrome.windows.get(windowId, { populate: true }, (win) => {
        if (chrome.runtime.lastError || !win || !win.tabs) return;
        const activeTab = win.tabs.find(t => t.active);
        if (activeTab) evaluateTabState(activeTab);
      });
    }
  });
});

// Distraction check evaluation
function evaluateTabState(tab) {
  if (!tab || !tab.url) return;

  chrome.storage.local.get(["studySessionActive", "distractionBlacklist", "distractionLogs"], (res) => {
    const active = res.studySessionActive || false;
    const blacklist = res.distractionBlacklist || ["facebook.com", "instagram.com", "twitter.com", "reddit.com", "tiktok.com"];
    let logs = res.distractionLogs || {};

    if (!active) {
      stopDistractionTiming(logs);
      return;
    }

    let domain = "";
    try {
      const urlObj = new URL(tab.url);
      domain = urlObj.hostname.replace("www.", "");
    } catch (e) {
      domain = "";
    }

    const matchedBlacklistDomain = blacklist.find(d => domain === d || domain.endsWith("." + d));

    if (matchedBlacklistDomain) {
      if (currentDistractionDomain !== matchedBlacklistDomain) {
        stopDistractionTiming(logs);

        currentDistractionDomain = matchedBlacklistDomain;
        distractionStartTime = Date.now();

        // Pause session in Side Panel & Active Tab
        notifyDistractionStart(matchedBlacklistDomain);
      }
    } else {
      // Safe tab
      if (currentDistractionDomain) {
        stopDistractionTiming(logs);
        notifyDistractionEnd();
      }
    }
  });
}

function stopDistractionTiming(logs) {
  if (currentDistractionDomain && distractionStartTime) {
    const durationMs = Date.now() - distractionStartTime;
    const seconds = Math.round(durationMs / 1000);

    if (seconds > 0) {
      logs[currentDistractionDomain] = (logs[currentDistractionDomain] || 0) + seconds;
      chrome.storage.local.set({ distractionLogs: logs });
    }
  }
  currentDistractionDomain = null;
  distractionStartTime = null;
}

function notifyDistractionStart(domain) {
  // 1. Tell Side Panel to pause Pomodoro timer
  chrome.runtime.sendMessage({ type: "heg_distraction_start", domain: domain }, () => {
    if (chrome.runtime.lastError) { /* ignore closed panel warning */ }
  });

  // 2. Tell Active Tab Content Script to blur screen and show pause modal
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, { type: "heg_distraction_start", domain: domain }, () => {
        if (chrome.runtime.lastError) { /* ignore inactive tab warnings */ }
      });
    }
  });
}

function notifyDistractionEnd() {
  // 1. Tell Side Panel to resume Pomodoro timer
  chrome.runtime.sendMessage({ type: "heg_distraction_end" }, () => {
    if (chrome.runtime.lastError) { /* ignore closed panel warning */ }
  });

  // 2. Tell Active Tab Content Script to clear blur overlays
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, { type: "heg_distraction_end" }, () => {
        if (chrome.runtime.lastError) { /* ignore inactive tab warnings */ }
      });
    }
  });
}
