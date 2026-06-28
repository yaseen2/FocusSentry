// Main World Webpage Execution Script - GazeReader
// Runs directly inside the target website context to execute WebGazer and draw spotlight graphics.

// --- Global States ---
window.webgazerInitialized = false;
let sessionActive = false;
let trackingActive = false;
let isCalibrated = false;
let calibrationClicks = {};
let totalClicksNeeded = 45;
let gazeHistory = [];
const GAZE_HISTORY_LIMIT = 5;
let smoothedGaze = { x: 0, y: 0 };

// Focus / HEG telemetry timers
let lastGazeReceiveTime = Date.now();
let lastUserInteractionTime = Date.now();
let lastActivityPingTime = Date.now();
let isCurrentlyDistracted = false;
let preWarningActive = false;
let preWarningSecondsLeft = 5;
let preWarningIntervalId = null;

let preferences = {
  showDot: true,
  autoScroll: true
};

// --- Injected Overlays Creation ---
function createPageOverlays() {
  if (document.getElementById("heg-gaze-pointer-dot")) return;

  // 1. Create Canvas Spotlight Overlay
  const canvas = document.createElement("canvas");
  canvas.id = "heg-spotlight-canvas";
  canvas.className = "heg-overlay-system";
  canvas.style.position = "fixed";
  canvas.style.top = "0";
  canvas.style.left = "0";
  canvas.style.width = "100vw";
  canvas.style.height = "100vh";
  canvas.style.pointerEvents = "none";
  canvas.style.zIndex = "100000";
  canvas.style.display = "none";
  document.body.appendChild(canvas);

  const resizeCanvas = () => {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
  };
  window.addEventListener("resize", resizeCanvas);
  resizeCanvas();

  // 2. Create Pointer Dot
  const pointer = document.createElement("div");
  pointer.id = "heg-gaze-pointer-dot";
  pointer.className = "heg-overlay-system";
  pointer.style.position = "fixed";
  pointer.style.width = "12px";
  pointer.style.height = "12px";
  pointer.style.background = "rgba(244, 63, 94, 0.85)";
  pointer.style.border = "2px solid white";
  pointer.style.borderRadius = "50%";
  pointer.style.boxShadow = "0 0 10px rgba(244, 63, 94, 0.8)";
  pointer.style.pointerEvents = "none";
  pointer.style.zIndex = "100010";
  pointer.style.display = "none";
  pointer.style.transform = "translate(-50%, -50%)";
  document.body.appendChild(pointer);

  // 3. Create Pre-Warning Banner
  const banner = document.createElement("div");
  banner.id = "heg-pre-warning-banner";
  banner.className = "heg-overlay-system";
  banner.style.display = "none";
  banner.innerHTML = `
    <span class="heg-banner-text">
      🕒 Focus check: Move mouse or scroll to dismiss... <strong id="heg-countdown-number">5</strong>s
    </span>
  `;
  document.body.appendChild(banner);

  // 4. Create Distraction Block Card Overlay
  const blocker = document.createElement("div");
  blocker.id = "heg-locked-overlay";
  blocker.className = "heg-overlay-system";
  blocker.style.display = "none";
  blocker.innerHTML = `
    <div class="heg-block-card">
      <div class="heg-block-icon">⚠️</div>
      <h2>Study Session Paused!</h2>
      <p id="heg-block-reason">Please return your focus to GazeReader.</p>
      <button class="heg-btn" id="heg-btn-resume">Resume Session</button>
    </div>
  `;
  document.body.appendChild(blocker);

  document.getElementById("heg-btn-resume").addEventListener("click", () => {
    triggerResetPing();
  });

  // 5. Create Calibration Canvas Overlay
  const calibOverlay = document.createElement("div");
  calibOverlay.id = "heg-calibration-overlay";
  calibOverlay.className = "heg-overlay-system";
  calibOverlay.style.display = "none";
  calibOverlay.innerHTML = `
    <div class="heg-calib-modal">
      <h2>Calibrating Gaze Model 🎯</h2>
      <p>Look directly at each of the 9 red rings and click them 5 times.</p>
    </div>
    <div class="heg-calib-point" id="hegPt1" style="top:10%; left:10%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt2" style="top:10%; left:50%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt3" style="top:10%; left:90%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt4" style="top:50%; left:10%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt5" style="top:50%; left:50%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt6" style="top:50%; left:90%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt7" style="top:90%; left:10%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt8" style="top:90%; left:50%;"><span>5</span></div>
    <div class="heg-calib-point" id="hegPt9" style="top:90%; left:90%;"><span>5</span></div>
  `;
  document.body.appendChild(calibOverlay);

  // Setup click listeners for calibration rings
  document.querySelectorAll(".heg-calib-point").forEach(pt => {
    const ptId = pt.id;
    calibrationClicks[ptId] = 0;

    pt.addEventListener("click", (e) => {
      if (calibrationClicks[ptId] < 5) {
        calibrationClicks[ptId]++;
        pt.setAttribute("data-clicks", calibrationClicks[ptId]);
        pt.querySelector("span").innerText = 5 - calibrationClicks[ptId];

        // Feed coordinates directly to local WebGazer model running on the page
        if (typeof webgazer !== "undefined") {
          webgazer.recordScreenPosition(e.clientX, e.clientY, 'mousedown');
        }

        // Send click count to side panel bridge
        let total = 0;
        Object.keys(calibrationClicks).forEach(k => {
          total += calibrationClicks[k];
        });
        
        window.postMessage({
          source: "GAZE_MAIN_WORLD",
          type: "calibration_clicks_update",
          total: total
        }, "*");

        if (calibrationClicks[ptId] >= 5) {
          pt.style.transform = "translate(-50%, -50%) scale(0)";
          pt.style.opacity = "0";
          pt.style.pointerEvents = "none";
        }

        if (total >= totalClicksNeeded) {
          finishCalibration(true);
        }
      }
    });
  });

  // 6. Create Draggable, Floating Webcam Preview Card
  const webcamCard = document.createElement("div");
  webcamCard.id = "heg-webcam-card";
  webcamCard.className = "heg-overlay-system";
  webcamCard.innerHTML = `
    <div class="heg-webcam-header">
      <span>📷 Eye Tracker Feed</span>
      <span class="heg-webcam-toggle" id="heg-webcam-toggle-btn">_</span>
    </div>
    <div class="heg-webcam-body">
      <div id="webgazerVideoContainerCustom">
        <div style="font-size: 9px; color:#64748b; padding:10px; text-align:center;">Camera standby...</div>
      </div>
    </div>
  `;
  document.body.appendChild(webcamCard);

  // Webcam drag handles
  const header = webcamCard.querySelector(".heg-webcam-header");
  let isDragging = false;
  let dragOffset = { x: 0, y: 0 };

  header.addEventListener("mousedown", (e) => {
    if (e.target.id === "heg-webcam-toggle-btn") return;
    isDragging = true;
    dragOffset.x = e.clientX - webcamCard.offsetLeft;
    dragOffset.y = e.clientY - webcamCard.offsetTop;
    header.style.cursor = "grabbing";
  });

  document.addEventListener("mousemove", (e) => {
    if (!isDragging) return;
    let x = e.clientX - dragOffset.x;
    let y = e.clientY - dragOffset.y;
    x = Math.max(10, Math.min(window.innerWidth - webcamCard.offsetWidth - 10, x));
    y = Math.max(10, Math.min(window.innerHeight - webcamCard.offsetHeight - 10, y));
    webcamCard.style.left = `${x}px`;
    webcamCard.style.top = `${y}px`;
    webcamCard.style.bottom = "auto";
    webcamCard.style.right = "auto";
  });

  document.addEventListener("mouseup", () => {
    isDragging = false;
    header.style.cursor = "grab";
  });

  document.getElementById("heg-webcam-toggle-btn").addEventListener("click", () => {
    webcamCard.classList.toggle("collapsed");
    const isCollapsed = webcamCard.classList.contains("collapsed");
    document.getElementById("heg-webcam-toggle-btn").innerText = isCollapsed ? "🗖" : "_";
  });
}

// --- 1. Bridge: Extension (Side Panel/Background) -> Webpage DOM ---
window.addEventListener("message", (event) => {
  if (event.source !== window) return;

  const msg = event.data;
  if (!msg || msg.source !== "GAZE_EXTENSION") return;

  createPageOverlays();

  if (msg.type === "start_calibration") {
    document.getElementById("heg-calibration-overlay").style.display = "flex";
    
    // Reset point DOM elements
    document.querySelectorAll(".heg-calib-point").forEach(pt => {
      pt.style.display = "flex";
      pt.style.transform = "translate(-50%, -50%) scale(1)";
      pt.style.opacity = "1";
      pt.style.pointerEvents = "auto";
      pt.setAttribute("data-clicks", "0");
      pt.querySelector("span").innerText = "5";
    });

    Object.keys(calibrationClicks).forEach(k => {
      calibrationClicks[k] = 0;
    });

    initWebGazer();
  }

  if (msg.type === "skip_calibration" || msg.type === "heg_calibration_end") {
    document.getElementById("heg-calibration-overlay").style.display = "none";
    finishCalibration(false);
  }

  if (msg.type === "session_start") {
    sessionActive = true;
    trackingActive = true;
    isCalibrated = (msg.data && msg.data.calibrated === true);

    dismissDistraction();
    dismissPreWarning();
    
    // Show spotlight canvas only if calibrated
    if (isCalibrated) {
      document.getElementById("heg-spotlight-canvas").style.display = "block";
    } else {
      document.getElementById("heg-spotlight-canvas").style.display = "none";
    }
    initWebGazer();
  }

  if (msg.type === "session_stop" || msg.type === "session_reset") {
    sessionActive = false;
    trackingActive = false;
    dismissDistraction();
    dismissPreWarning();
    
    // Hide visual spotlight
    document.getElementById("heg-spotlight-canvas").style.display = "none";
    document.getElementById("heg-gaze-pointer-dot").style.display = "none";

    // Pause WebGazer to turn off camera light
    if (window.webgazerInitialized && typeof webgazer !== "undefined") {
      webgazer.pause();
      sendHegStatus("Standby (Off)", "red");
    }
  }

  if (msg.type === "heg_distraction_start") {
    triggerDistraction("Distracted site active");
  }

  if (msg.type === "heg_distraction_end") {
    dismissDistraction();
    dismissPreWarning();
  }

  if (msg.type === "update_preferences") {
    preferences.showDot = msg.data.showDot;
    preferences.autoScroll = msg.data.autoScroll;
  }
});

// --- Local Calibration Functions ---
function finishCalibration(calibrated) {
  isCalibrated = calibrated;
  document.getElementById("heg-calibration-overlay").style.display = "none";

  // Hide drawing overlays if calibration skipped
  if (!isCalibrated) {
    document.getElementById("heg-spotlight-canvas").style.display = "none";
    document.getElementById("heg-gaze-pointer-dot").style.display = "none";
  }

  window.postMessage({
    source: "GAZE_MAIN_WORLD",
    type: "page_calibration_complete",
    calibrated: calibrated
  }, "*");

  sendHegStatus("Tracking Active", "green");
}

function sendHegStatus(status, colorClass) {
  window.postMessage({
    source: "GAZE_MAIN_WORLD",
    type: "heg_status_update",
    status: status,
    colorClass: colorClass
  }, "*");
}

// --- Active Telemetry User Activity Reporter ---
function triggerResetPing() {
  lastUserInteractionTime = Date.now();
  if (preWarningActive) {
    dismissPreWarning();
  }
  if (isCurrentlyDistracted) {
    dismissDistraction();
  }

  const now = Date.now();
  if (now - lastActivityPingTime > 1200) {
    lastActivityPingTime = now;
    window.postMessage({
      source: "GAZE_MAIN_WORLD",
      type: "user_interaction_ping"
    }, "*");
  }
}

// Listen to page inputs
window.addEventListener("mousemove", triggerResetPing);
window.addEventListener("click", triggerResetPing);
window.addEventListener("keydown", triggerResetPing);
window.addEventListener("scroll", triggerResetPing, { passive: true });

// --- WebGazer Initialization inside page context ---
function initWebGazer() {
  if (window.webgazerInitialized) {
    // If already initialized, just resume tracking
    if (typeof webgazer !== "undefined") {
      webgazer.resume();
      sendHegStatus("Tracking Active", "green");
    }
    return;
  }

  if (typeof webgazer === "undefined") {
    sendHegStatus("Gaze API Missing", "red");
    return;
  }

  sendHegStatus("Compiling Model...", "yellow");

  // Keep checking for video feed tags created by WebGazer to dock them
  let dockAttempts = 0;
  const dockTimer = setInterval(() => {
    dockAttempts++;
    const success = dockWebGazerFeed();
    if (success || dockAttempts > 50) {
      clearInterval(dockTimer);
    }
  }, 200);

  try {
    webgazer.setGazeListener((data, elapsedTime) => {
      lastGazeReceiveTime = Date.now();

      if (data == null || !trackingActive || isCurrentlyDistracted) return;

      smoothedGaze = smoothGaze(data);
      handleGazeTick(smoothedGaze.x, smoothedGaze.y);
    });

    webgazer.showPredictionPoints(false);
    webgazer.showVideoPreview(true);

    webgazer.begin().then(() => {
      window.webgazerInitialized = true;
      sendHegStatus("Tracking Active", "green");
    }).catch(err => {
      console.error("Camera access error inside page context:", err);
      sendHegStatus("No Camera Access", "red");
    });
  } catch (err) {
    console.error("WebGazer begin error in page:", err);
    sendHegStatus("Tracker Error", "red");
  }
}

function dockWebGazerFeed() {
  const video = document.getElementById("webgazerVideoFeed");
  const canvas = document.getElementById("webgazerVideoCanvas");
  const container = document.getElementById("webgazerVideoContainerCustom");

  if (video && container) {
    video.style.position = "relative";
    video.style.width = "100%";
    video.style.height = "100%";
    video.style.objectFit = "cover";
    video.style.top = "0";
    video.style.left = "0";
    video.style.margin = "0";

    if (canvas) {
      canvas.style.position = "absolute";
      canvas.style.width = "100%";
      canvas.style.height = "100%";
      canvas.style.objectFit = "cover";
      canvas.style.top = "0";
      canvas.style.left = "0";
      canvas.style.margin = "0";
    }

    container.innerHTML = "";
    container.appendChild(video);
    if (canvas) container.appendChild(canvas);
    return true;
  }
  return false;
}

function smoothGaze(data) {
  gazeHistory.push({ x: data.x, y: data.y });
  if (gazeHistory.length > GAZE_HISTORY_LIMIT) {
    gazeHistory.shift();
  }

  let totalX = 0, totalY = 0;
  gazeHistory.forEach(pt => {
    totalX += pt.x;
    totalY += pt.y;
  });

  return {
    x: totalX / gazeHistory.length,
    y: totalY / gazeHistory.length
  };
}

// --- Gaze highlight and scrolling engine ---
function handleGazeTick(x, y) {
  if (!isCalibrated) {
    // If not calibrated, do not draw spotlights or pointer dots
    const pointer = document.getElementById("heg-gaze-pointer-dot");
    if (pointer) pointer.style.display = "none";
    const canvas = document.getElementById("heg-spotlight-canvas");
    if (canvas) canvas.style.display = "none";
    return;
  }

  // Update Pointer Dot
  const pointer = document.getElementById("heg-gaze-pointer-dot");
  if (pointer && preferences.showDot && sessionActive) {
    pointer.style.display = "block";
    pointer.style.left = `${x}px`;
    pointer.style.top = `${y}px`;
  } else if (pointer) {
    pointer.style.display = "none";
  }

  // Draw Spotlight canvas overlay
  const canvas = document.getElementById("heg-spotlight-canvas");
  if (canvas && sessionActive) {
    canvas.style.display = "block";
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Dark screen overlay (40% transparent dimming)
    ctx.fillStyle = "rgba(10, 8, 18, 0.45)";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Draw clear circle (100px radius spotlight)
    ctx.globalCompositeOperation = "destination-out";
    ctx.beginPath();
    ctx.arc(x, y, 100, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalCompositeOperation = "source-over";
  }

  // Gaze auto scrolling wiggles
  if (preferences.autoScroll && sessionActive) {
    const scrollBottomThreshold = window.innerHeight * 0.72;
    const scrollTopThreshold = window.innerHeight * 0.12;

    if (y > scrollBottomThreshold) {
      const intensity = (y - scrollBottomThreshold) / (window.innerHeight - scrollBottomThreshold);
      const step = Math.ceil(intensity * 4);
      window.scrollBy(0, step);
    } else if (y < scrollTopThreshold && window.scrollY > 0) {
      const intensity = (scrollTopThreshold - y) / scrollTopThreshold;
      const step = Math.ceil(intensity * 4);
      window.scrollBy(0, -step);
    }
  }
}

// --- Local Focus / HEG Checker loop ---
function pageHEGIntervalLoop() {
  if (!sessionActive || isCurrentlyDistracted) return;

  // 25-Second Active Interaction Override
  const now = Date.now();
  const timeSinceUserInteraction = now - lastUserInteractionTime;
  if (timeSinceUserInteraction < 25000) {
    dismissPreWarning();
    dismissDistraction();
    return;
  }

  const timeSinceGazeUpdate = now - lastGazeReceiveTime;

  if (timeSinceGazeUpdate > 8000) {
    // Fallback to user activity proofs
    sendHegStatus("Activity Guard", "yellow");
    if (timeSinceUserInteraction > 45000) {
      triggerDistraction("System Idle");
    }
  } else {
    // Camera is active
    sendHegStatus("Tracking Active", "green");
    if (timeSinceGazeUpdate > 6000) {
      triggerPreWarning("Face Missing");
    } else {
      // Check coordinates bounds (+150px buffer margins)
      // Note: Skip coord check if calibration skipped to avoid false triggers
      if (isCalibrated && (smoothedGaze.x < -150 || smoothedGaze.x > window.innerWidth + 150 ||
          smoothedGaze.y < -150 || smoothedGaze.y > window.innerHeight + 150)) {
        triggerPreWarning("Looking Away");
      } else {
        // Gaze is active and inside boundaries
        dismissPreWarning();
        dismissDistraction();
      }
    }
  }
}

function triggerPreWarning(reason) {
  if (isCurrentlyDistracted || preWarningActive) return;

  preWarningActive = true;
  preWarningSecondsLeft = 5;

  sendHegStatus("Focus Drift Alert", "yellow");

  document.body.classList.add("heg-pre-warning");
  document.getElementById("heg-pre-warning-banner").style.display = "block";
  document.getElementById("heg-countdown-number").innerText = preWarningSecondsLeft;

  if (preWarningIntervalId) clearInterval(preWarningIntervalId);
  preWarningIntervalId = setInterval(() => {
    preWarningSecondsLeft--;
    document.getElementById("heg-countdown-number").innerText = preWarningSecondsLeft;

    if (preWarningSecondsLeft <= 0) {
      clearInterval(preWarningIntervalId);
      preWarningIntervalId = null;
      preWarningActive = false;
      document.body.classList.remove("heg-pre-warning");
      document.getElementById("heg-pre-warning-banner").style.display = "none";
      
      triggerDistraction(reason);
    }
  }, 1000);
}

function dismissPreWarning() {
  if (!preWarningActive) return;

  preWarningActive = false;
  if (preWarningIntervalId) {
    clearInterval(preWarningIntervalId);
    preWarningIntervalId = null;
  }

  document.body.classList.remove("heg-pre-warning");
  document.getElementById("heg-pre-warning-banner").style.display = "none";
  sendHegStatus("Tracking Active", "green");
}

function triggerDistraction(reason) {
  if (isCurrentlyDistracted) return;

  isCurrentlyDistracted = true;
  sendHegStatus("Focus Paused", "red");

  // Show page blur and lock overlays
  document.body.classList.add("heg-blurred");
  document.getElementById("heg-locked-overlay").style.display = "flex";
  document.getElementById("heg-block-reason").innerText = `Reason: ${reason}. Move cursor or click resume to return.`;

  // Hide spotlights
  document.getElementById("heg-spotlight-canvas").style.display = "none";
  document.getElementById("heg-gaze-pointer-dot").style.display = "none";
}

function dismissDistraction() {
  if (!isCurrentlyDistracted) return;

  isCurrentlyDistracted = false;

  document.body.classList.remove("heg-blurred");
  document.getElementById("heg-locked-overlay").style.display = "none";

  if (sessionActive && isCalibrated) {
    document.getElementById("heg-spotlight-canvas").style.display = "block";
  }

  sendHegStatus("Tracking Active", "green");
}

// Start HEG check loop
setInterval(pageHEGIntervalLoop, 1000);
