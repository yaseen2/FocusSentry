import sys
import os
import time
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from PyQt6.QtWidgets import QApplication, QSystemTrayIcon, QMenu
from PyQt6.QtCore import QObject, QTimer, Qt
from PyQt6.QtGui import QIcon, QPixmap, QPainter, QColor, QBrush

# Application Component Imports
import database
import hooks
from tracker import FaceGazeTracker
from overlay import DesktopOverlay
from dashboard import StudyDashboard

# Audio alarm synthesis library
import winsound

# Thread-safe global event flag for Android mobile pings
phone_active_event = threading.Event()

class PhoneSensorHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return  # Suppress logging console output to keep stdout clean

    def do_GET(self):
        if self.path == "/ping":
            phone_active_event.set()
            self.send_response(200)
            self.send_header("Content-type", "text/plain")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b"OK")
        else:
            self.send_response(404)
            self.end_headers()

class GazeReaderApp(QObject):
    def __init__(self):
        super().__init__()
        
        # State metrics
        self.pomodoro_active = False
        self.study_time_left = 50 * 60
        self.pomodoro_phase = "FOCUS"
        
        self.active_study_seconds = 0
        self.distracted_study_seconds = 0
        self.phone_pickup_warnings = 0
        self.last_tracker_update = 0.0
        self.tracker_data = {"yaw": 0.0, "pitch": 0.0, "roll": 0.0, "face_present": False}
        self.yaw_offset = 0.0
        self.pitch_offset = 0.0
        
        # 1. Initialize UI Controls
        self.dashboard = StudyDashboard()
        
        # Spawns fullscreen overlays on all active screens
        self.overlays = []
        self.init_screen_overlays()
        
        # 2. Initialize Camera Tracker Thread
        self.tracker_thread = FaceGazeTracker()
        self.tracker_thread.status_updated.connect(self.dashboard.set_tracker_status)
        self.tracker_thread.gaze_data_updated.connect(self.handle_tracker_gaze)
        self.tracker_thread.frame_ready.connect(self.dashboard.update_camera_frame)
        
        # 3. Setup System Tray Icon
        self.tray_icon = QSystemTrayIcon(self)
        self.init_tray_icon()
        
        # 4. Timer Loops
        self.clock_timer = QTimer(self)
        self.clock_timer.timeout.connect(self.study_clock_tick)
        self.clock_timer.start(1000) # 1-Second Loop
        
        # 5. Start background HTTP listener for Android phone sensor
        self.server = None
        self.server_thread = threading.Thread(target=self.run_sensor_server, daemon=True)
        self.server_thread.start()
        self.run_adb_reverse()
        
        # Bind dashboard actions
        self.dashboard.pomodoro_toggled.connect(self.handle_pomodoro_toggle)
        self.dashboard.blacklist_updated.connect(self.handle_blacklist_update)
        self.dashboard.set_center_requested.connect(self.calibrate_center_baseline)
        self.dashboard.resume_suspend_detected.connect(self.run_adb_reverse)
        
        # Bind overlays back resume actions
        for ov in self.overlays:
            ov.resume_requested.connect(self.dismiss_lock_state)

        # Show dashboard initially unless run with --silent flag
        if "--silent" not in sys.argv:
            self.dashboard.show()

    def init_screen_overlays(self):
        """Creates transparent PyQt6 overlay widgets for every connected monitor screen."""
        screens = QApplication.screens()
        for scr in screens:
            geom = scr.geometry()
            overlay = DesktopOverlay(geom)
            self.overlays.append(overlay)

    def init_tray_icon(self):
        # Draw a custom tray icon dynamically (dark blue circle with white center)
        pix = QPixmap(32, 32)
        pix.fill(Qt.GlobalColor.transparent)
        
        painter = QPainter(pix)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        
        # Draw outer circle
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QBrush(QColor("#6366f1")))
        painter.drawEllipse(2, 2, 28, 28)
        
        # Draw inner "pupil"
        painter.setBrush(QBrush(QColor("#f8fafc")))
        painter.drawEllipse(10, 10, 12, 12)
        
        painter.end()
        
        self.tray_icon.setIcon(QIcon(pix))
        self.tray_icon.setToolTip("GazeReader - Study Companion")
        
        # Context Menu
        menu = QMenu()
        open_action = menu.addAction("🎯 Open Dashboard")
        open_action.triggered.connect(self.dashboard.show)
        
        self.pomo_action = menu.addAction("🍅 Start Pomodoro")
        self.pomo_action.triggered.connect(self.dashboard.toggle_pomodoro)
        
        menu.addSeparator()
        exit_action = menu.addAction("🚪 Exit Application")
        exit_action.triggered.connect(self.terminate_app)
        
        self.tray_icon.setContextMenu(menu)
        self.tray_icon.show()
        
        # Double click tray restores window
        self.tray_icon.activated.connect(self.tray_icon_activated)

    def tray_icon_activated(self, reason):
        if reason == QSystemTrayIcon.ActivationReason.DoubleClick:
            self.dashboard.show()
            self.dashboard.raise_()
            self.dashboard.activateWindow()

    def handle_tracker_gaze(self, yaw, pitch, roll, is_face_present, is_eye_distracted):
        self.tracker_data = {
            "yaw": yaw,
            "pitch": pitch,
            "roll": roll,
            "face_present": is_face_present,
            "eye_distracted": is_eye_distracted
        }
        self.last_tracker_update = time.time()

    def handle_pomodoro_toggle(self, active):
        self.pomodoro_active = active
        if active:
            self.study_time_left = 50 * 60
            self.pomodoro_phase = "FOCUS"
            self.active_study_seconds = 0
            self.distracted_study_seconds = 0
            self.phone_pickup_warnings = 0
            self.pomo_action.setText("⏹️ Stop Pomodoro")
            
            # Start camera tracker thread
            self.tracker_thread.start()
            QTimer.singleShot(2500, self.calibrate_center_baseline)
        else:
            self.pomo_action.setText("🍅 Start Pomodoro")
            self.save_study_session()
            
            # Shut down camera to turn off sensor light
            self.tracker_thread.stop()
            self.clear_all_overlays()

    def handle_blacklist_update(self):
        # Hot-reload blacklist definitions in check loops
        pass

    # --- Central Focus & Distraction Engine (1-Second clock tick) ---
    def study_clock_tick(self):
        # 1. Process blacklist monitor checks
        is_distracted, detail_reason = hooks.check_is_distracted_active(self.dashboard.blacklist_items)
        
        if self.pomodoro_active:
            # Handle Pomodoro countdown
            self.study_time_left -= 1
            self.dashboard.update_timer_label(self.study_time_left, self.pomodoro_phase)
            
            if self.study_time_left <= 0:
                self.transition_pomodoro_phase()

        # Exit early if we are not actively inside a Study Pomodoro
        if not self.pomodoro_active:
            return

        # Distraction logs updates
        if is_distracted:
            self.distracted_study_seconds += 1
            database.log_distraction(detail_reason, 1)
            self.trigger_all_overlays_lockout(detail_reason)
            return

        # Check Android Mobile Phone Activity Event
        phone_triggered = False
        if phone_active_event.is_set():
            phone_triggered = True
            phone_active_event.clear()
            
        if phone_triggered:
            self.phone_pickup_warnings += 1
            if self.phone_pickup_warnings >= 3:
                database.log_distraction("Phone Distraction Limit Exceeded (3 Pickups)", 1)
                import ctypes
                try:
                    ctypes.windll.user32.LockWorkStation()
                except Exception as e:
                    print("Failed to lock Windows workstation:", e)
                self.trigger_all_overlays_lockout("Phone Limit Exceeded (3/3 Pickups) - Computer Locked")
            else:
                self.trigger_all_overlays_warning(f"Phone Activity Detected ({self.phone_pickup_warnings}/3 Pickups)")
            return

        # 2. Analyze Camera Landmark Feed
        now = time.time()
        time_since_camera_feed = now - self.last_tracker_update
        idle_seconds = hooks.get_system_idle_time()
        
        # Posture-Drift Auto-Adaptation:
        # If user is actively typing or wiggling mouse, they must be looking at the screen.
        # Slowly slide the center baseline towards their current pose.
        if idle_seconds < 1.0 and self.tracker_data["face_present"] and time_since_camera_feed <= 2.0:
            self.yaw_offset = self.yaw_offset * 0.92 + self.tracker_data["yaw"] * 0.08
            self.pitch_offset = self.pitch_offset * 0.92 + self.tracker_data["pitch"] * 0.08
            self.dashboard.lbl_calib_state.setText(f"Auto-Center: Y:{self.yaw_offset:.1f}°, P:{self.pitch_offset:.1f}°")
        
        if time_since_camera_feed > 8.0:
            # Camera is standby/blocked: Fallback to global input activity check
            self.dashboard.set_tracker_status("yellow", "Activity Guard")
            if idle_seconds < 25.0:
                # User is active elsewhere, keep on-task
                self.active_study_seconds += 1
                self.clear_all_overlays()
            elif idle_seconds > 45.0:
                # System is completely idle
                self.distracted_study_seconds += 1
                self.trigger_all_overlays_lockout("System Idle")
        else:
            # Camera is active: Evaluate head pose landmarks immediately
            yaw_limit = float(database.get_setting("yaw_threshold", 18.0))
            pitch_limit = float(database.get_setting("pitch_threshold", 14.0))
            
            calibrated_yaw = self.tracker_data["yaw"] - self.yaw_offset
            calibrated_pitch = self.tracker_data["pitch"] - self.pitch_offset
            
            if not self.tracker_data["face_present"]:
                self.trigger_all_overlays_warning("Face Missing")
            elif self.tracker_data.get("eye_distracted", False):
                self.trigger_all_overlays_warning("Eye Rolling Detected")
            elif abs(calibrated_yaw) > yaw_limit or abs(calibrated_pitch) > pitch_limit:
                self.trigger_all_overlays_warning("Looking Away")
            else:
                # User is on-task
                self.active_study_seconds += 1
                self.clear_all_overlays()

        # Handle active countdown triggers to Stage 3 Lockout
        for ov in self.overlays:
            if ov.state == "PRE_WARNING" and ov.pre_warning_seconds <= 0:
                self.trigger_all_overlays_lockout("Focus Drift Time expired")

    # --- Overlay Coordinates Managers ---
    def trigger_all_overlays_warning(self, reason):
        # Play a synthesized alert chime if enabled
        if database.get_setting("chime_enabled", True):
            # Win32 Beep synthesis
            winsound.Beep(520, 100)
            
        delay = int(database.get_setting("warning_delay", 5))
        for ov in self.overlays:
            ov.show_pre_warning(reason, delay)

    def trigger_all_overlays_lockout(self, reason):
        for ov in self.overlays:
            ov.show_distracted(reason)

    def clear_all_overlays(self):
        for ov in self.overlays:
            ov.clear_overlay()

    def dismiss_lock_state(self):
        # Dismiss locked frames and reset interaction pings
        self.clear_all_overlays()
        # Fake a user wiggle to give them 25 seconds window
        pass

    # --- Pomodoro Transitions ---
    def transition_pomodoro_phase(self):
        # Play completion chime sound
        winsound.MessageBeep(winsound.MB_ICONASTERISK)
        
        if self.pomodoro_phase == "FOCUS":
            self.pomodoro_phase = "BREAK"
            self.study_time_left = 10 * 60 # 10-minute break
            self.save_study_session()
            self.dashboard.update_journal_metrics()
        else:
            self.pomodoro_phase = "FOCUS"
            self.study_time_left = 50 * 60 # 50-minute study
            
        self.dashboard.update_timer_label(self.study_time_left, self.pomodoro_phase)

    # --- Relational SQL logs writers ---
    def save_study_session(self):
        day_str = time.strftime("%a") # e.g. Mon, Tue
        database.save_session(day_str, self.active_study_seconds, self.distracted_study_seconds)
        self.active_study_seconds = 0
        self.distracted_study_seconds = 0
        self.dashboard.update_journal_metrics()

    def calibrate_center_baseline(self):
        # Read current head pose angles and store them as offsets
        if self.tracker_data["face_present"]:
            self.yaw_offset = self.tracker_data["yaw"]
            self.pitch_offset = self.tracker_data["pitch"]
            self.dashboard.lbl_calib_state.setText(f"Center: Y:{self.yaw_offset:.1f}°, P:{self.pitch_offset:.1f}°")

    def run_sensor_server(self):
        try:
            self.server = HTTPServer(("0.0.0.0", 5001), PhoneSensorHTTPHandler)
            self.server.serve_forever()
        except Exception as e:
            print("Failed to start phone sensor HTTP server:", e)

    def run_adb_reverse(self):
        try:
            adb_path = r"C:\Users\ThinkPad\AppData\Local\Android\Sdk\platform-tools\adb.exe"
            if os.path.exists(adb_path):
                import subprocess
                subprocess.run([adb_path, "reverse", "tcp:5001", "tcp:5001"], capture_output=True)
        except Exception as e:
            print("Failed to auto-configure ADB reverse:", e)

    def terminate_app(self):
        if self.server:
            try:
                self.server.shutdown()
                self.server.server_close()
            except Exception:
                pass
                
        # Save session stats before exiting
        if self.pomodoro_active:
            self.save_study_session()
            self.tracker_thread.stop()
            
        self.tray_icon.hide()
        
        # Close overlay widgets
        for ov in self.overlays:
            ov.close()
            
        # Stop background loops
        self.clock_timer.stop()
        
        # Shut down QApplication main window event queues
        QApplication.quit()
        sys.exit(0)

if __name__ == "__main__":
    app = QApplication(sys.argv)
    # Prevent QApplication from automatically closing when dashboard window is hidden
    app.setQuitOnLastWindowClosed(False)
    
    app_controller = GazeReaderApp()
    sys.exit(app.exec())
