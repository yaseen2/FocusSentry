// GazeReader Side Panel Logic Controller
// Coordinates study timers, preferences, and lists metrics.

// --- State Variables ---
let pomodoroActive = false;
let pomodoroTimeLeft = 50 * 60; // 50 mins
let pomodoroPhase = "FOCUS";     // FOCUS, BREAK, INACTIVE
let activeStudySeconds = 0;
let distractedStudySeconds = 0;
let blacklistDomains = ["facebook.com", "instagram.com", "twitter.com", "reddit.com", "tiktok.com"];
let isCurrentlyDistracted = false;

// Preferences
let preferences = {
  showDot: true,
  autoScroll: true
};

// --- Initialization ---
document.addEventListener("DOMContentLoaded", () => {
  loadPreferences();
  initUIEventListeners();
  initChromeMessageListeners();
  loadBlacklist();
  populateJournalData();
  
  // Start the study clock loop
  setInterval(studySessionClockTick, 1000);
});

function loadPreferences() {
  const saved = localStorage.getItem("gazeReaderPrefs");
  if (saved) {
    try {
      preferences = { ...preferences, ...JSON.parse(saved) };
    } catch (e) {
      console.error(e);
    }
  }
  document.getElementById("chk-show-dot").checked = preferences.showDot;
  document.getElementById("chk-auto-scroll").checked = preferences.autoScroll;

  // Let webpage overlays know current preferences after short rendering delay
  setTimeout(sendOverlayPrefsToTab, 1000);
}

function savePreferences() {
  localStorage.setItem("gazeReaderPrefs", JSON.stringify(preferences));
  sendOverlayPrefsToTab();
}

function sendOverlayPrefsToTab() {
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, {
        type: "update_preferences",
        showDot: preferences.showDot,
        autoScroll: preferences.autoScroll
      }, () => {
        if (chrome.runtime.lastError) { /* ignore */ }
      });
    }
  });
}

// --- UI Controls Event Bindings ---
function initUIEventListeners() {
  // Calibration commands
  document.getElementById("btn-recalibrate").addEventListener("click", triggerCalibrationStart);
  document.getElementById("btn-skip-calib").addEventListener("click", triggerCalibrationSkip);

  // Pomodoro toggles
  document.getElementById("btn-toggle-pomodoro").addEventListener("click", togglePomodoro);

  // Blacklist manager
  document.getElementById("btn-add-blacklist").addEventListener("click", () => {
    const input = document.getElementById("txt-new-blacklist");
    addBlacklistDomain(input.value);
    input.value = "";
  });
  document.getElementById("txt-new-blacklist").addEventListener("keypress", (e) => {
    if (e.key === "Enter") {
      addBlacklistDomain(e.target.value);
      e.target.value = "";
    }
  });

  // Reset Journal
  document.getElementById("btn-journal-reset").addEventListener("click", () => {
    if (confirm("Reset study journal history?")) {
      resetStudyHistory();
    }
  });

  // Preference toggles
  document.getElementById("chk-show-dot").addEventListener("change", (e) => {
    preferences.showDot = e.target.checked;
    savePreferences();
  });
  document.getElementById("chk-auto-scroll").addEventListener("change", (e) => {
    preferences.autoScroll = e.target.checked;
    savePreferences();
  });
}

// --- Message listeners (Bridge coordinate pings and calibrations) ---
function initChromeMessageListeners() {
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    
    // 1. Calibration clicks from webpage
    if (request.type === "page_calibration_clicks_update") {
      document.getElementById("calib-progress").innerText = `Clicks: ${request.total} / 45`;
      sendResponse({ success: true });
      return true;
    }

    // 2. Calibration finished in webpage
    if (request.type === "page_calibration_complete") {
      if (request.calibrated) {
        localStorage.setItem("gazeReaderCalibrated", "true");
        document.getElementById("calib-progress").innerText = "Calibrated";
      } else {
        localStorage.setItem("gazeReaderCalibrated", "false");
        document.getElementById("calib-progress").innerText = "Skipped (Presence Only)";
      }
      setWebgazerStatusLabel("green", "Tracking Active");
      sendResponse({ success: true });
      return true;
    }

    // 3. Status updates from active tab HEG engine
    if (request.type === "page_heg_status_update") {
      setWebgazerStatusLabel(request.colorClass, request.status);
      sendResponse({ success: true });
      return true;
    }

    // 4. Tab distraction events from background.js
    if (request.type === "heg_distraction_start") {
      triggerDistraction(`Distracted (Domain: ${request.domain})`);
      sendResponse({ success: true });
      return true;
    }

    if (request.type === "heg_distraction_end") {
      dismissDistraction();
      sendResponse({ success: true });
      return true;
    }
  });
}

// --- Web Audio Synthesizer Beeps ---
function playFocusChime() {
  try {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return;
    const ctx = new AudioContextClass();
    const osc = ctx.createOscillator();
    const gainNode = ctx.createGain();
    
    osc.type = "sine";
    gainNode.gain.setValueAtTime(0.12, ctx.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.45);
    
    osc.connect(gainNode);
    gainNode.connect(ctx.destination);
    
    osc.frequency.setValueAtTime(620, ctx.currentTime);
    osc.frequency.setValueAtTime(480, ctx.currentTime + 0.15);
    
    osc.start(ctx.currentTime);
    osc.stop(ctx.currentTime + 0.45);
  } catch (e) {
    console.warn(e);
  }
}

// --- Study session clock tick (1-Second Loop) ---
function studySessionClockTick() {
  if (isCurrentlyDistracted) {
    if (pomodoroActive) {
      distractedStudySeconds++;
    }
    return;
  }

  // Increment active study counters
  if (pomodoroActive && pomodoroPhase === "FOCUS") {
    activeStudySeconds++;
  }

  // Pomodoro countdown timer
  if (pomodoroActive) {
    pomodoroTimeLeft--;
    updatePomodoroTimerDisplay();

    if (pomodoroTimeLeft <= 0) {
      handlePomodoroPhaseTransition();
    }
  }
}

function triggerDistraction(reason) {
  if (isCurrentlyDistracted) return;

  isCurrentlyDistracted = true;
  setWebgazerStatusLabel("red", "Focus Paused");
  playFocusChime();
}

function dismissDistraction() {
  if (!isCurrentlyDistracted) return;

  isCurrentlyDistracted = false;
  setWebgazerStatusLabel("green", "Tracking Active");
}

// --- Action Triggers (Sent to webpage) ---
function triggerCalibrationStart() {
  document.getElementById("calib-progress").innerText = "Clicks: 0 / 45";
  setWebgazerStatusLabel("yellow", "Calibrating...");

  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, { type: "start_calibration" }, () => {
        if (chrome.runtime.lastError) {
          alert("GazeReader content script not ready. Please refresh the page tab.");
        }
      });
    }
  });
}

function triggerCalibrationSkip() {
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, { type: "skip_calibration" }, () => {
        if (chrome.runtime.lastError) { /* ignore */ }
      });
    }
  });

  localStorage.setItem("gazeReaderCalibrated", "false");
  document.getElementById("calib-progress").innerText = "Skipped (Presence Only)";
  setWebgazerStatusLabel("green", "Tracking Active");
}

function setWebgazerStatusLabel(color, text) {
  const dot = document.getElementById("status-dot");
  const label = document.getElementById("status-text");
  
  dot.className = `indicator-light ${color}`;
  label.innerText = text;
}

// --- Pomodoro study control functions ---
function togglePomodoro() {
  const btn = document.getElementById("btn-toggle-pomodoro");
  if (!pomodoroActive) {
    pomodoroActive = true;
    pomodoroPhase = "FOCUS";
    pomodoroTimeLeft = 50 * 60; // 50 mins

    btn.innerText = "⏹️ Stop Pomodoro";
    btn.className = "btn btn-danger";

    activeStudySeconds = 0;
    distractedStudySeconds = 0;

    updatePomodoroTimerDisplay();
    notifySessionStateToBackground(true);
  } else {
    pomodoroActive = false;
    pomodoroPhase = "INACTIVE";

    btn.innerText = "🍅 Start Pomodoro";
    btn.className = "btn btn-primary";

    document.getElementById("pomodoro-timer").innerText = "50:00";
    document.getElementById("pomodoro-phase").innerText = "FOCUS";

    saveSessionToJournal();
    notifySessionStateToBackground(false);
  }
}

function updatePomodoroTimerDisplay() {
  const display = document.getElementById("pomodoro-timer");
  const phaseLabel = document.getElementById("pomodoro-phase");
  
  const min = Math.floor(pomodoroTimeLeft / 60);
  const sec = pomodoroTimeLeft % 60;
  display.innerText = `${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
  
  phaseLabel.innerText = pomodoroPhase;
  phaseLabel.style.color = pomodoroPhase === "FOCUS" ? "var(--accent-color)" : "var(--text-muted)";
}

function handlePomodoroPhaseTransition() {
  playFocusChime();
  if (pomodoroPhase === "FOCUS") {
    pomodoroPhase = "BREAK";
    pomodoroTimeLeft = 10 * 60; // 10 mins
    alert("🍅 STUDY TIMER FINISHED! Take a 10-minute break.");
  } else {
    pomodoroPhase = "FOCUS";
    pomodoroTimeLeft = 50 * 60; // 50 mins study
    alert("🍅 BREAK FINISHED! Time to focus for 50 minutes.");
  }
  updatePomodoroTimerDisplay();
}

function notifySessionStateToBackground(active) {
  const type = active ? "start_session" : "stop_session";
  chrome.runtime.sendMessage({ type: type }, () => {
    if (chrome.runtime.lastError) { /* ignore */ }
  });

  // Tell webpage overlays to toggle tracking engine states
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs && tabs[0] && tabs[0].id) {
      chrome.tabs.sendMessage(tabs[0].id, {
        type: active ? "session_start" : "session_stop"
      }, () => {
        if (chrome.runtime.lastError) { /* ignore */ }
      });
    }
  });
}

// --- Distraction Blacklist Configuration Helpers ---
function loadBlacklist() {
  if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
    chrome.storage.local.get("distractionBlacklist", (res) => {
      if (res && res.distractionBlacklist) {
        blacklistDomains = res.distractionBlacklist;
      }
      renderBlacklistTags();
    });
  } else {
    renderBlacklistTags();
  }
}

function renderBlacklistTags() {
  const container = document.getElementById("blacklist-tags-container");
  if (!container) return;
  container.innerHTML = "";

  blacklistDomains.forEach(domain => {
    const pill = document.createElement("div");
    pill.className = "blacklist-tag";
    pill.innerHTML = `
      <span>${domain}</span>
      <span class="blacklist-remove" data-domain="${domain}">×</span>
    `;
    container.appendChild(pill);
  });

  container.querySelectorAll(".blacklist-remove").forEach(btn => {
    btn.addEventListener("click", (e) => {
      removeBlacklistDomain(e.target.dataset.domain);
    });
  });
}

function addBlacklistDomain(domain) {
  domain = domain.trim().toLowerCase().replace("www.", "");
  if (domain === "youtube.com") {
    alert("YouTube cannot be blacklisted (study lectures allowed).");
    return;
  }
  if (domain && !blacklistDomains.includes(domain)) {
    blacklistDomains.push(domain);
    if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
      chrome.storage.local.set({ distractionBlacklist: blacklistDomains }, () => {
        renderBlacklistTags();
        notifySessionStateToBackground(pomodoroActive);
      });
    } else {
      renderBlacklistTags();
    }
  }
}

function removeBlacklistDomain(domain) {
  blacklistDomains = blacklistDomains.filter(d => d !== domain);
  if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
    chrome.storage.local.set({ distractionBlacklist: blacklistDomains }, () => {
      renderBlacklistTags();
      notifySessionStateToBackground(pomodoroActive);
    });
  } else {
    renderBlacklistTags();
  }
}

// --- Study Journal Logger & history chart builders ---
function saveSessionToJournal() {
  if (activeStudySeconds === 0) return;

  const today = new Date().toLocaleDateString("en-US", { weekday: 'short' });
  
  if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
    chrome.storage.local.get("studyJournalHistory", (res) => {
      let history = res.studyJournalHistory || [];
      
      const newEntry = {
        day: today,
        active: activeStudySeconds,
        distracted: distractedStudySeconds,
        timestamp: Date.now()
      };
      
      history.push(newEntry);
      if (history.length > 7) history.shift();
      
      chrome.storage.local.set({ studyJournalHistory: history }, () => {
        activeStudySeconds = 0;
        distractedStudySeconds = 0;
        populateJournalData();
      });
    });
  } else {
    activeStudySeconds = 0;
    distractedStudySeconds = 0;
  }
}

function populateJournalData() {
  if (typeof chrome === "undefined" || !chrome.storage || !chrome.storage.local) {
    document.getElementById("journal-val-active").innerText = "42m";
    document.getElementById("journal-val-distracted").innerText = "12m";
    document.getElementById("journal-val-ratio").innerText = "77%";
    renderMockStudyChart();
    return;
  }

  chrome.storage.local.get("studyJournalHistory", (res) => {
    const history = res.studyJournalHistory || [];
    
    let totalActive = 0;
    let totalDistracted = 0;

    history.forEach(e => {
      totalActive += e.active || 0;
      totalDistracted += e.distracted || 0;
    });

    const activeMin = Math.round(totalActive / 60);
    const distractedMin = Math.round(totalDistracted / 60);
    const efficiency = totalActive + totalDistracted > 0 
      ? Math.round((totalActive / (totalActive + totalDistracted)) * 100) 
      : 100;

    document.getElementById("journal-val-active").innerText = `${activeMin}m`;
    document.getElementById("journal-val-distracted").innerText = `${distractedMin}m`;
    document.getElementById("journal-val-ratio").innerText = `${efficiency}%`;

    renderStudyHistoryChart(history);
  });
}

function renderStudyHistoryChart(history) {
  const chart = document.getElementById("journal-chart");
  chart.innerHTML = "";

  if (history.length === 0) {
    chart.innerHTML = `<div style="font-size:8px; color:var(--text-muted); text-align:center; width:100%; margin-top:20px;">Start studying to log metrics!</div>`;
    return;
  }

  let maxTime = 1;
  history.forEach(e => {
    const sum = (e.active || 0) + (e.distracted || 0);
    if (sum > maxTime) maxTime = sum;
  });

  history.forEach(e => {
    const bar = document.createElement("div");
    bar.className = "mini-chart-bar";

    const activeH = Math.round(((e.active || 0) / maxTime) * 45);
    const distH = Math.round(((e.distracted || 0) / maxTime) * 45);

    const activeMin = Math.round((e.active || 0) / 60);
    const distMin = Math.round((e.distracted || 0) / 60);

    bar.innerHTML = `
      <div class="bar-seg-distracted" style="height:${Math.max(2, distH)}px; width:100%;" title="Distracted: ${distMin}m"></div>
      <div class="bar-seg-active" style="height:${Math.max(2, activeH)}px; width:100%;" title="Focused: ${activeMin}m"></div>
    `;
    chart.appendChild(bar);
  });
}

function resetStudyHistory() {
  if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
    chrome.storage.local.set({ studyJournalHistory: [], distractionLogs: {} }, () => {
      populateJournalData();
    });
  }
}

function renderMockStudyChart() {
  const chart = document.getElementById("journal-chart");
  chart.innerHTML = "";
  for (let idx = 0; idx < 7; idx++) {
    const bar = document.createElement("div");
    bar.className = "mini-chart-bar";
    const activeVal = 10 + idx * 4;
    const distVal = 5 + (idx % 2) * 5;
    bar.innerHTML = `
      <div class="bar-seg-distracted" style="height:${distVal}px; width:100%;"></div>
      <div class="bar-seg-active" style="height:${activeVal}px; width:100%;"></div>
    `;
    chart.appendChild(bar);
  }
}
