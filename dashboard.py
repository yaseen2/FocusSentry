import sys
import os
import cv2
import numpy as np
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QLineEdit, 
    QListWidget, QListWidgetItem, QCheckBox, QFrame, QSlider, QComboBox
)
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QFont, QImage, QPixmap, QColor
import ctypes
from ctypes import wintypes

# Database Connectors
import database

class StudyDashboard(QWidget):
    pomodoro_toggled = pyqtSignal(bool)
    blacklist_updated = pyqtSignal()
    recalibrate_requested = pyqtSignal()
    set_center_requested = pyqtSignal()
    resume_suspend_detected = pyqtSignal()
    adapt_hotkey_pressed = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.setFixedSize(920, 640)
        self.pomodoro_active = False
        self.study_time_left = 50 * 60
        self.pomodoro_phase = "FOCUS"
        self.blacklist_items = []
        
        self.setWindowTitle("GazeReader - Study Control Panel")
        self.setStyleSheet("""
            QWidget {
                background-color: #0b0f19;
                color: #e2e8f0;
                font-family: 'Segoe UI', -apple-system, Roboto, sans-serif;
            }
            QLabel {
                font-size: 12px;
                color: #94a3b8;
            }
            QLineEdit {
                background-color: #1e293b;
                border: 1px solid rgba(255, 255, 255, 0.05);
                border-radius: 8px;
                padding: 8px 12px;
                color: #f8fafc;
                font-size: 12px;
            }
            QLineEdit:focus {
                border-color: #6366f1;
                background-color: #0f172a;
            }
            QPushButton {
                font-size: 12px;
                font-weight: 600;
                border-radius: 8px;
                padding: 8px 16px;
                border: none;
            }
            QFrame.card {
                background-color: #131c2e;
                border: 1px solid rgba(255, 255, 255, 0.04);
                border-radius: 12px;
            }
            QCheckBox {
                font-size: 11px;
                color: #94a3b8;
            }
            QCheckBox::indicator {
                width: 14px;
                height: 14px;
                border: 1px solid rgba(255, 255, 255, 0.15);
                border-radius: 3px;
                background: #1e293b;
            }
            QCheckBox::indicator:checked {
                background: #6366f1;
                border-color: #6366f1;
            }
            QSlider::groove:horizontal {
                border: none;
                height: 4px;
                background: #1e293b;
                border-radius: 2px;
            }
            QSlider::handle:horizontal {
                background: #6366f1;
                border: none;
                width: 12px;
                height: 12px;
                margin: -4px 0;
                border-radius: 6px;
            }
            QSlider::handle:horizontal:hover {
                background: #8b5cf6;
            }
            QListWidget {
                background-color: #0f172a;
                border: 1px solid rgba(255, 255, 255, 0.03);
                border-radius: 8px;
                padding: 6px;
                color: #f43f5e;
                font-weight: 600;
                font-size: 12px;
            }
            QScrollBar:vertical {
                border: none;
                background: #0f172a;
                width: 6px;
                margin: 0px;
                border-radius: 3px;
            }
            QScrollBar::handle:vertical {
                background: #334155;
                min-height: 20px;
                border-radius: 3px;
            }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
                border: none;
                background: none;
            }
        """)

        self.init_ui()
        self.load_blacklist_data()
        self.update_journal_metrics()
        self.register_global_hotkey()

    def init_ui(self):
        # Main Layout: 3 Columns
        outer_layout = QVBoxLayout(self)
        outer_layout.setContentsMargins(24, 20, 24, 24)
        outer_layout.setSpacing(16)

        # 1. Header Row
        header_layout = QHBoxLayout()
        logo_lbl = QLabel("👁️  GazeReader", self)
        logo_lbl.setFont(QFont("Outfit", 22, QFont.Weight.Bold))
        logo_lbl.setStyleSheet("color: #6366f1; font-weight: bold;")
        
        badge_lbl = QLabel("DESKTOP DASHBOARD", self)
        badge_lbl.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        badge_lbl.setStyleSheet("""
            background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #6366f1, stop:1 #8b5cf6);
            color: white;
            border-radius: 6px;
            padding: 4px 10px;
            font-weight: 800;
        """)
        header_layout.addWidget(logo_lbl)
        header_layout.addStretch()
        header_layout.addWidget(badge_lbl)
        outer_layout.addLayout(header_layout)

        # Columns container
        cols_layout = QHBoxLayout()
        cols_layout.setSpacing(20)

        # ========================================================
        # COLUMN 1: TRACKING & SETTINGS
        # ========================================================
        col1_layout = QVBoxLayout()
        col1_layout.setSpacing(16)

        # Camera Card
        self.status_card = QFrame(self)
        self.status_card.setProperty("class", "card")
        status_layout = QVBoxLayout(self.status_card)
        status_layout.setContentsMargins(16, 16, 16, 16)
        status_layout.setSpacing(12)

        status_title = QLabel("TRACKING SENSORS", self.status_card)
        status_title.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        status_title.setStyleSheet("color: #475569; font-weight: 800; letter-spacing: 1px;")
        status_layout.addWidget(status_title)

        # Large Camera Preview Box (Width proportional to 4:3, height 170)
        self.video_lbl = QLabel(self.status_card)
        self.video_lbl.setFixedHeight(170)
        self.video_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.video_lbl.setStyleSheet("background-color: #020617; border-radius: 8px; border: 1px solid rgba(255,255,255,0.02);")
        self.video_lbl.setText("Camera Standby (Preview disabled)")
        self.video_lbl.setFont(QFont("Inter", 10))
        self.video_lbl.setStyleSheet("color: #475569; background-color: #020617; border-radius: 8px;")
        status_layout.addWidget(self.video_lbl)

        # Status indicators (Row 1)
        status_row = QHBoxLayout()
        status_row.setSpacing(6)
        self.dot = QWidget(self.status_card)
        self.dot.setFixedSize(10, 10)
        self.dot.setStyleSheet("background-color: #f43f5e; border-radius: 5px;")
        
        self.status_lbl = QLabel("Standby (Off)", self.status_card)
        self.status_lbl.setFont(QFont("Inter", 11, QFont.Weight.Bold))
        self.status_lbl.setStyleSheet("color: #cbd5e1; font-weight: bold;")
        
        status_row.addWidget(self.dot)
        status_row.addWidget(self.status_lbl)
        status_row.addStretch()
        status_layout.addLayout(status_row)
        
        # Center calibration button and value label (Row 2)
        calib_row = QHBoxLayout()
        self.btn_set_center = QPushButton("🎯 Set Center", self.status_card)
        self.btn_set_center.setObjectName("btn_set_center")
        self.btn_set_center.setFixedSize(95, 28)
        self.btn_set_center.setStyleSheet("""
            QPushButton#btn_set_center {
                background-color: #1e293b;
                color: #6366f1;
                border: 1px solid rgba(99, 102, 241, 0.3);
                border-radius: 6px;
                padding: 2px 6px;
                font-family: 'Inter';
                font-size: 11px;
                font-weight: bold;
            }
            QPushButton#btn_set_center:hover {
                background-color: rgba(99, 102, 241, 0.15);
                color: #f8fafc;
            }
        """)
        self.btn_set_center.clicked.connect(self.set_center_requested.emit)
        
        self.lbl_calib_state = QLabel("Center: Y:0°, P:0°", self.status_card)
        self.lbl_calib_state.setFont(QFont("Inter", 9))
        self.lbl_calib_state.setStyleSheet("color: #64748b;")
        
        calib_row.addWidget(self.btn_set_center)
        calib_row.addSpacing(10)
        calib_row.addWidget(self.lbl_calib_state)
        calib_row.addStretch()
        status_layout.addLayout(calib_row)
        
        # Phone Link Status (Row 3)
        phone_row = QHBoxLayout()
        phone_row.setSpacing(6)
        
        self.phone_dot = QWidget(self.status_card)
        self.phone_dot.setFixedSize(10, 10)
        self.phone_dot.setStyleSheet("background-color: #64748b; border-radius: 5px;")
        
        self.phone_lbl = QLabel("Phone Link: Offline", self.status_card)
        self.phone_lbl.setFont(QFont("Inter", 10, QFont.Weight.Medium))
        self.phone_lbl.setStyleSheet("color: #94a3b8;")
        
        phone_row.addWidget(self.phone_dot)
        phone_row.addWidget(self.phone_lbl)
        phone_row.addStretch()
        status_layout.addLayout(phone_row)
        
        col1_layout.addWidget(self.status_card)

        # Preferences Card
        pref_card = QFrame(self)
        pref_card.setProperty("class", "card")
        pref_layout = QVBoxLayout(pref_card)
        pref_layout.setContentsMargins(12, 10, 12, 10)
        pref_layout.setSpacing(6)
        
        pref_title = QLabel("⚙️ SYSTEM SETTINGS & SENSITIVITY", pref_card)
        pref_title.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        pref_title.setStyleSheet("color: #475569; font-weight: 800; letter-spacing: 1px; margin-bottom: 2px;")
        pref_layout.addWidget(pref_title)
        
        # Checkboxes row 1 (Startup & Chimes side-by-side)
        cb_row1 = QHBoxLayout()
        self.chk_startup = QCheckBox("Startup boot", pref_card)
        self.chk_startup.setChecked(database.get_setting("startup_enabled", False))
        self.chk_startup.stateChanged.connect(self.toggle_startup_preference)
        
        self.chk_chime = QCheckBox("Warning chimes", pref_card)
        self.chk_chime.setChecked(database.get_setting("chime_enabled", True))
        self.chk_chime.stateChanged.connect(lambda state: database.save_setting("chime_enabled", state == 2))
        
        cb_row1.addWidget(self.chk_startup)
        cb_row1.addWidget(self.chk_chime)
        pref_layout.addLayout(cb_row1)
        
        # Checkboxes row 2 (Preview & Link Mode side-by-side)
        cb_row2 = QHBoxLayout()
        self.chk_preview = QCheckBox("Video preview", pref_card)
        self.chk_preview.setChecked(database.get_setting("preview_enabled", False))
        self.chk_preview.stateChanged.connect(self.toggle_video_preview)
        
        self.cmb_link_mode = QComboBox(pref_card)
        self.cmb_link_mode.addItems(["Local Sockets", "Firebase Cloud"])
        current_mode = database.get_setting("phone_link_mode", "LOCAL")
        self.cmb_link_mode.setCurrentIndex(1 if current_mode == "CLOUD" else 0)
        self.cmb_link_mode.setStyleSheet("""
            QComboBox {
                background: rgba(255,255,255,0.02);
                border: 1px solid rgba(255,255,255,0.08);
                border-radius: 4px;
                color: #f8fafc;
                padding: 2px 6px;
                font-size: 11px;
                min-width: 110px;
            }
        """)
        self.cmb_link_mode.currentIndexChanged.connect(self.update_link_mode)
        
        cb_row2.addWidget(self.chk_preview)
        cb_row2.addWidget(self.cmb_link_mode)
        pref_layout.addLayout(cb_row2)

        # Firebase configuration fields (Container widget to toggle visibility easily)
        self.fb_container = QWidget(pref_card)
        fb_lay = QVBoxLayout(self.fb_container)
        fb_lay.setContentsMargins(0, 4, 0, 4)
        fb_lay.setSpacing(6)

        # Firebase URL (Label above Input)
        fb_url_lbl = QLabel("Firebase Database URL:", self.fb_container)
        fb_url_lbl.setFont(QFont("Outfit", 7, QFont.Weight.Bold))
        fb_url_lbl.setStyleSheet("color: #64748b; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px;")
        self.txt_fb_url = QLineEdit(self.fb_container)
        self.txt_fb_url.setPlaceholderText("https://database-name.firebaseio.com")
        self.txt_fb_url.setText(database.get_setting("firebase_url", ""))
        self.txt_fb_url.setStyleSheet("""
            QLineEdit {
                background: rgba(255,255,255,0.02);
                border: 1px solid rgba(255,255,255,0.08);
                border-radius: 4px;
                color: #f8fafc;
                padding: 4px 6px;
                font-size: 11px;
            }
            QLineEdit:focus {
                border: 1px solid #6366f1;
            }
        """)
        self.txt_fb_url.textChanged.connect(lambda text: database.save_setting("firebase_url", text))
        fb_lay.addWidget(fb_url_lbl)
        fb_lay.addWidget(self.txt_fb_url)

        # Firebase User Path & Test Button (Side-by-side row!)
        fb_row2 = QHBoxLayout()
        
        path_col = QVBoxLayout()
        fb_path_lbl = QLabel("User Path / ID:", self.fb_container)
        fb_path_lbl.setFont(QFont("Outfit", 7, QFont.Weight.Bold))
        fb_path_lbl.setStyleSheet("color: #64748b; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px;")
        self.txt_fb_path = QLineEdit(self.fb_container)
        self.txt_fb_path.setPlaceholderText("e.g. yaseen")
        self.txt_fb_path.setText(database.get_setting("firebase_path", "yaseen"))
        self.txt_fb_path.setStyleSheet("""
            QLineEdit {
                background: rgba(255,255,255,0.02);
                border: 1px solid rgba(255,255,255,0.08);
                border-radius: 4px;
                color: #f8fafc;
                padding: 4px 6px;
                font-size: 11px;
            }
            QLineEdit:focus {
                border: 1px solid #6366f1;
            }
        """)
        self.txt_fb_path.textChanged.connect(lambda text: database.save_setting("firebase_path", text))
        path_col.addWidget(fb_path_lbl)
        path_col.addWidget(self.txt_fb_path)
        fb_row2.addLayout(path_col, 2)

        test_col = QVBoxLayout()
        test_col.setAlignment(Qt.AlignmentFlag.AlignBottom)
        
        test_btn_status_row = QHBoxLayout()
        self.btn_test_fb = QPushButton("🧪 Test Connection", self.fb_container)
        self.btn_test_fb.setFixedHeight(22)
        self.btn_test_fb.setStyleSheet("""
            QPushButton {
                background: #1e293b;
                color: #e2e8f0;
                border: 1px solid rgba(255,255,255,0.08);
                border-radius: 4px;
                font-family: 'Inter';
                font-size: 10px;
                font-weight: bold;
                padding: 0px 8px;
            }
            QPushButton:hover {
                background: #334155;
            }
        """)
        self.btn_test_fb.clicked.connect(self.test_firebase_connection)
        
        self.lbl_fb_test_status = QLabel("", self.fb_container)
        self.lbl_fb_test_status.setFont(QFont("Outfit", 7, QFont.Weight.Bold))
        self.lbl_fb_test_status.setStyleSheet("padding: 2px 6px; border-radius: 4px;")
        
        test_btn_status_row.addWidget(self.btn_test_fb)
        test_btn_status_row.addWidget(self.lbl_fb_test_status)
        test_col.addLayout(test_btn_status_row)
        fb_row2.addLayout(test_col, 3)
        
        fb_lay.addLayout(fb_row2)
        pref_layout.addWidget(self.fb_container)
        self.fb_container.setVisible(current_mode == "CLOUD")

        # Yaw Slider Row
        yaw_row = QHBoxLayout()
        yaw_lbl = QLabel("Yaw Limit:", pref_card)
        yaw_lbl.setFont(QFont("Inter", 8))
        self.yaw_slider = QSlider(Qt.Orientation.Horizontal, pref_card)
        self.yaw_slider.setRange(5, 100)
        self.yaw_slider.setValue(int(database.get_setting("yaw_threshold", 18)))
        self.yaw_val_lbl = QLabel(f"{self.yaw_slider.value()}°", pref_card)
        self.yaw_val_lbl.setFixedWidth(24)
        self.yaw_val_lbl.setFont(QFont("Inter", 8))
        self.yaw_val_lbl.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.yaw_slider.valueChanged.connect(self.update_yaw_threshold)
        yaw_row.addWidget(yaw_lbl)
        yaw_row.addWidget(self.yaw_slider)
        yaw_row.addWidget(self.yaw_val_lbl)
        pref_layout.addLayout(yaw_row)

        # Pitch Slider Row
        pitch_row = QHBoxLayout()
        pitch_lbl = QLabel("Pitch Limit:", pref_card)
        pitch_lbl.setFont(QFont("Inter", 8))
        self.pitch_slider = QSlider(Qt.Orientation.Horizontal, pref_card)
        self.pitch_slider.setRange(5, 100)
        self.pitch_slider.setValue(int(database.get_setting("pitch_threshold", 14)))
        self.pitch_val_lbl = QLabel(f"{self.pitch_slider.value()}°", pref_card)
        self.pitch_val_lbl.setFixedWidth(24)
        self.pitch_val_lbl.setFont(QFont("Inter", 8))
        self.pitch_val_lbl.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.pitch_slider.valueChanged.connect(self.update_pitch_threshold)
        pitch_row.addWidget(pitch_lbl)
        pitch_row.addWidget(self.pitch_slider)
        pitch_row.addWidget(self.pitch_val_lbl)
        pref_layout.addLayout(pitch_row)

        # Eye Roll Slider Row
        eye_row = QHBoxLayout()
        eye_lbl = QLabel("Eye Roll:", pref_card)
        eye_lbl.setFont(QFont("Inter", 8))
        self.eye_slider = QSlider(Qt.Orientation.Horizontal, pref_card)
        self.eye_slider.setRange(20, 45)
        self.eye_slider.setValue(int(database.get_setting("eye_roll_threshold", 35)))
        self.eye_val_lbl = QLabel(f"{self.eye_slider.value()}%", pref_card)
        self.eye_val_lbl.setFixedWidth(24)
        self.eye_val_lbl.setFont(QFont("Inter", 8))
        self.eye_val_lbl.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.eye_slider.valueChanged.connect(self.update_eye_threshold)
        eye_row.addWidget(eye_lbl)
        eye_row.addWidget(self.eye_slider)
        eye_row.addWidget(self.eye_val_lbl)
        pref_layout.addLayout(eye_row)

        # Delay Slider Row
        delay_row = QHBoxLayout()
        delay_lbl = QLabel("Alert Delay:", pref_card)
        delay_lbl.setFont(QFont("Inter", 8))
        self.delay_slider = QSlider(Qt.Orientation.Horizontal, pref_card)
        self.delay_slider.setRange(1, 10)
        self.delay_slider.setValue(int(database.get_setting("warning_delay", 4)))
        self.delay_val_lbl = QLabel(f"{self.delay_slider.value()}s", pref_card)
        self.delay_val_lbl.setFixedWidth(24)
        self.delay_val_lbl.setFont(QFont("Inter", 8))
        self.delay_val_lbl.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.delay_slider.valueChanged.connect(self.update_warning_delay)
        delay_row.addWidget(delay_lbl)
        delay_row.addWidget(self.delay_slider)
        delay_row.addWidget(self.delay_val_lbl)
        pref_layout.addLayout(delay_row)
        
        col1_layout.addWidget(pref_card)
        cols_layout.addLayout(col1_layout)

        # ========================================================
        # COLUMN 2: POMODORO & BLACKLIST
        # ========================================================
        col2_layout = QVBoxLayout()
        col2_layout.setSpacing(16)

        # Pomodoro Card
        self.pomo_card = QFrame(self)
        self.pomo_card.setProperty("class", "card")
        pomo_layout = QVBoxLayout(self.pomo_card)
        pomo_layout.setContentsMargins(16, 16, 16, 16)
        pomo_layout.setSpacing(10)

        pomo_title = QLabel("STUDY POMODORO CLOCK", self.pomo_card)
        pomo_title.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        pomo_title.setStyleSheet("color: #475569; font-weight: 800; letter-spacing: 1px;")
        pomo_layout.addWidget(pomo_title)

        # Timer Display
        timer_box = QVBoxLayout()
        self.timer_lbl = QLabel("50:00", self.pomo_card)
        self.timer_lbl.setFont(QFont("Outfit", 48, QFont.Weight.Bold))
        self.timer_lbl.setStyleSheet("color: #f8fafc; font-weight: bold;")
        self.timer_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        
        self.phase_lbl = QLabel("FOCUS PHASE", self.pomo_card)
        self.phase_lbl.setFont(QFont("Outfit", 10, QFont.Weight.Bold))
        self.phase_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.phase_lbl.setStyleSheet("color: #6366f1; letter-spacing: 1.5px; font-weight: 800;")
        timer_box.addWidget(self.timer_lbl)
        timer_box.addWidget(self.phase_lbl)
        pomo_layout.addLayout(timer_box)

        # Start button
        self.pomo_btn = QPushButton("🍅 Start Pomodoro Session", self.pomo_card)
        self.pomo_btn.setFixedHeight(42)
        self.pomo_btn.setStyleSheet("""
            QPushButton {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #6366f1, stop:1 #8b5cf6);
                color: white;
                font-size: 13px;
                font-weight: bold;
            }
            QPushButton:hover {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #4f46e5, stop:1 #7c3aed);
            }
        """)
        self.pomo_btn.clicked.connect(self.toggle_pomodoro)
        pomo_layout.addWidget(self.pomo_btn)
        col2_layout.addWidget(self.pomo_card)

        # Blacklist Card (Expanded Height!)
        self.black_card = QFrame(self)
        self.black_card.setProperty("class", "card")
        black_layout = QVBoxLayout(self.black_card)
        black_layout.setContentsMargins(16, 16, 16, 16)
        black_layout.setSpacing(10)

        black_title = QLabel("🚫 DISTRACTION BLACKLIST KEYWORDS", self.black_card)
        black_title.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        black_title.setStyleSheet("color: #475569; font-weight: 800; letter-spacing: 1px;")
        black_layout.addWidget(black_title)

        # List Widget (Spacious size!)
        self.blacklist_widget = QListWidget(self.black_card)
        self.blacklist_widget.setFixedHeight(120)
        self.blacklist_widget.setToolTip("Select a keyword and press 'Delete' to remove it.")
        black_layout.addWidget(self.blacklist_widget)

        # Add Row
        add_layout = QHBoxLayout()
        add_layout.setSpacing(8)
        self.txt_add = QLineEdit(self.black_card)
        self.txt_add.setPlaceholderText("e.g. facebook, reddit, game")
        self.txt_add.setFixedHeight(36)
        
        self.btn_add = QPushButton("Add Keyword", self.black_card)
        self.btn_add.setFixedHeight(36)
        self.btn_add.setStyleSheet("""
            QPushButton {
                background-color: #1e293b;
                color: #cbd5e1;
                border: 1px solid rgba(255, 255, 255, 0.05);
                font-size: 12px;
            }
            QPushButton:hover {
                background-color: #334155;
                color: #f8fafc;
            }
        """)
        self.btn_add.clicked.connect(self.add_blacklist_item)
        self.txt_add.returnPressed.connect(self.add_blacklist_item)
        add_layout.addWidget(self.txt_add)
        add_layout.addWidget(self.btn_add)
        black_layout.addLayout(add_layout)
        col2_layout.addWidget(self.black_card)
        
        cols_layout.addLayout(col2_layout)

        # ========================================================
        # COLUMN 3: STUDY JOURNAL STATS & CHARTS
        # ========================================================
        col3_layout = QVBoxLayout()
        col3_layout.setSpacing(16)

        self.journal_card = QFrame(self)
        self.journal_card.setProperty("class", "card")
        journal_layout = QVBoxLayout(self.journal_card)
        journal_layout.setContentsMargins(16, 16, 16, 16)
        journal_layout.setSpacing(14)

        # Title and Filter Row
        title_row = QHBoxLayout()
        
        journal_title = QLabel("📈 STUDY JOURNAL SUMMARY", self.journal_card)
        journal_title.setFont(QFont("Outfit", 8, QFont.Weight.Bold))
        journal_title.setStyleSheet("color: #475569; font-weight: 800; letter-spacing: 1px;")
        title_row.addWidget(journal_title)
        
        # Filter buttons container
        self.filter_group = QFrame(self.journal_card)
        self.filter_group.setStyleSheet("""
            QFrame {
                background: rgba(255,255,255,0.02);
                border: 1px solid rgba(255,255,255,0.04);
                border-radius: 6px;
            }
        """)
        filter_layout = QHBoxLayout(self.filter_group)
        filter_layout.setContentsMargins(2, 2, 2, 2)
        filter_layout.setSpacing(2)
        
        self.btn_filter_day = QPushButton("Day", self.filter_group)
        self.btn_filter_week = QPushButton("Week", self.filter_group)
        self.btn_filter_month = QPushButton("Month", self.filter_group)
        
        # Flat style for tab buttons
        button_style = """
            QPushButton {
                background: transparent;
                border: none;
                color: #64748b;
                font-family: 'Inter';
                font-size: 10px;
                font-weight: bold;
                padding: 4px 10px;
                border-radius: 4px;
            }
            QPushButton:hover {
                color: #f8fafc;
                background: rgba(255,255,255,0.02);
            }
            QPushButton[active="true"] {
                color: #ffffff;
                background: #6366f1;
            }
        """
        self.btn_filter_day.setStyleSheet(button_style)
        self.btn_filter_week.setStyleSheet(button_style)
        self.btn_filter_month.setStyleSheet(button_style)
        
        # Track current active filter mode
        self.current_filter_mode = "WEEK" # DAY, WEEK, MONTH
        self.btn_filter_week.setProperty("active", "true")
        
        filter_layout.addWidget(self.btn_filter_day)
        filter_layout.addWidget(self.btn_filter_week)
        filter_layout.addWidget(self.btn_filter_month)
        
        title_row.addWidget(self.filter_group, 0, Qt.AlignmentFlag.AlignRight)
        journal_layout.addLayout(title_row)
        
        self.btn_filter_day.clicked.connect(lambda: self.change_filter_mode("DAY"))
        self.btn_filter_week.clicked.connect(lambda: self.change_filter_mode("WEEK"))
        self.btn_filter_month.clicked.connect(lambda: self.change_filter_mode("MONTH"))

        # Metrics rows (stacked side-by-side, larger sizes!)
        grid_layout = QHBoxLayout()
        grid_layout.setSpacing(10)
        
        # Focused Card
        self.j_card1 = QFrame(self.journal_card)
        self.j_card1.setMinimumHeight(70)
        self.j_card1.setStyleSheet("background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.04); border-radius: 8px;")
        j1_lay = QVBoxLayout(self.j_card1)
        j1_lay.setContentsMargins(8, 10, 8, 10)
        j1_lay.setSpacing(2)
        self.lbl_j_active = QLabel("0m", self.j_card1)
        self.lbl_j_active.setFont(QFont("Outfit", 16, QFont.Weight.Bold))
        self.lbl_j_active.setStyleSheet("color: #6366f1; font-weight: bold;")
        self.lbl_j_active.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl_j1_sub = QLabel("Focused", self.j_card1)
        lbl_j1_sub.setFont(QFont("Inter", 9))
        lbl_j1_sub.setStyleSheet("color: #64748b;")
        lbl_j1_sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        j1_lay.addWidget(self.lbl_j_active)
        j1_lay.addWidget(lbl_j1_sub)
        
        # Distracted Card
        self.j_card2 = QFrame(self.journal_card)
        self.j_card2.setMinimumHeight(70)
        self.j_card2.setStyleSheet("background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.04); border-radius: 8px;")
        j2_lay = QVBoxLayout(self.j_card2)
        j2_lay.setContentsMargins(8, 10, 8, 10)
        j2_lay.setSpacing(2)
        self.lbl_j_distracted = QLabel("0m", self.j_card2)
        self.lbl_j_distracted.setFont(QFont("Outfit", 16, QFont.Weight.Bold))
        self.lbl_j_distracted.setStyleSheet("color: #f43f5e; font-weight: bold;")
        self.lbl_j_distracted.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl_j2_sub = QLabel("Distracted", self.j_card2)
        lbl_j2_sub.setFont(QFont("Inter", 9))
        lbl_j2_sub.setStyleSheet("color: #64748b;")
        lbl_j2_sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        j2_lay.addWidget(self.lbl_j_distracted)
        j2_lay.addWidget(lbl_j2_sub)

        # Efficiency Card
        self.j_card3 = QFrame(self.journal_card)
        self.j_card3.setMinimumHeight(70)
        self.j_card3.setStyleSheet("background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.04); border-radius: 8px;")
        j3_lay = QVBoxLayout(self.j_card3)
        j3_lay.setContentsMargins(8, 10, 8, 10)
        j3_lay.setSpacing(2)
        self.lbl_j_ratio = QLabel("100%", self.j_card3)
        self.lbl_j_ratio.setFont(QFont("Outfit", 16, QFont.Weight.Bold))
        self.lbl_j_ratio.setStyleSheet("color: #10b981; font-weight: bold;")
        self.lbl_j_ratio.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lbl_j3_sub = QLabel("Efficiency", self.j_card3)
        lbl_j3_sub.setFont(QFont("Inter", 9))
        lbl_j3_sub.setStyleSheet("color: #64748b;")
        lbl_j3_sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        j3_lay.addWidget(self.lbl_j_ratio)
        j3_lay.addWidget(lbl_j3_sub)

        grid_layout.addWidget(self.j_card1)
        grid_layout.addWidget(self.j_card2)
        grid_layout.addWidget(self.j_card3)
        journal_layout.addLayout(grid_layout)
        
        # Scroll Area for mini bar chart (to handle 24h or 30d without truncation)
        from PyQt6.QtWidgets import QScrollArea
        self.chart_scroll = QScrollArea(self.journal_card)
        self.chart_scroll.setFixedHeight(180)
        self.chart_scroll.setWidgetResizable(True)
        self.chart_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.chart_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.chart_scroll.setStyleSheet("""
            QScrollArea {
                background: rgba(255,255,255,0.01);
                border: none;
                border-top: 1px solid rgba(255,255,255,0.04);
            }
        """)
        
        self.chart_frame = QFrame()
        self.chart_frame.setStyleSheet("background: transparent;")
        self.chart_layout = QHBoxLayout(self.chart_frame)
        self.chart_layout.setContentsMargins(10, 20, 10, 8)
        self.chart_layout.setSpacing(10)
        self.chart_layout.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignBottom)
        
        self.chart_scroll.setWidget(self.chart_frame)
        journal_layout.addWidget(self.chart_scroll)
        col3_layout.addWidget(self.journal_card)
        
        cols_layout.addLayout(col3_layout)

        # Add columns to outer layout
        outer_layout.addLayout(cols_layout)

    # --- Video Preview Handling ---
    def toggle_video_preview(self, state):
        enabled = (state == 2)
        database.save_setting("preview_enabled", enabled)
        if not enabled:
            self.video_lbl.clear()
            self.video_lbl.setText("Camera Standby (Preview disabled)")

    def update_link_mode(self, index):
        mode = "CLOUD" if index == 1 else "LOCAL"
        database.save_setting("phone_link_mode", mode)
        self.fb_container.setVisible(mode == "CLOUD")

    def test_firebase_connection(self):
        # Read parameters directly from the UI inputs on the main thread
        fb_url = self.txt_fb_url.text().strip()
        fb_path = self.txt_fb_path.text().strip()

        self.lbl_fb_test_status.setText("TESTING...")
        self.lbl_fb_test_status.setStyleSheet("color: #ffffff; background: #475569; padding: 4px 8px; border-radius: 4px; font-family: 'Outfit'; font-size: 10px; font-weight: bold;")
        self.btn_test_fb.setEnabled(False)
        
        import threading
        def run_test(url_to_test, path_to_test):
            success = False
            error_msg = "Error"
            try:
                import requests
                # Safe type conversion & strip
                url_to_test = str(url_to_test).strip() if url_to_test else ""
                path_to_test = str(path_to_test).strip() if path_to_test else ""
                
                if not url_to_test or not path_to_test:
                    error_msg = "EMPTY FIELDS"
                else:
                    # Sanitize URL
                    if not url_to_test.startswith("http"):
                        url_to_test = "https://" + url_to_test
                    if url_to_test.endswith("/"):
                        url_to_test = url_to_test[:-1]
                        
                    test_url = f"{url_to_test}/users/{path_to_test}/test_signal.json"
                    
                    try:
                        resp = requests.put(test_url, json=True, timeout=5)
                        if resp.status_code in [200, 204]:
                            success = True
                        else:
                            error_msg = f"HTTP {resp.status_code}"
                    except Exception as e:
                        error_msg = "Offline/Timeout"
            except Exception as outer_e:
                error_msg = "Config Error"
                
            def update_ui():
                self.btn_test_fb.setEnabled(True)
                if success:
                    self.lbl_fb_test_status.setText("✅ SUCCESS")
                    self.lbl_fb_test_status.setStyleSheet("color: #ffffff; background: #16a34a; padding: 4px 8px; border-radius: 4px; font-family: 'Outfit'; font-size: 10px; font-weight: bold;")
                else:
                    self.lbl_fb_test_status.setText(f"❌ FAILED ({error_msg})")
                    self.lbl_fb_test_status.setStyleSheet("color: #ffffff; background: #dc2626; padding: 4px 8px; border-radius: 4px; font-family: 'Outfit'; font-size: 10px; font-weight: bold;")
            
            from PyQt6.QtCore import QTimer
            QTimer.singleShot(0, update_ui)
            
        threading.Thread(target=run_test, args=(fb_url, fb_path), daemon=True).start()

    def update_camera_frame(self, frame):
        if not database.get_setting("preview_enabled", False):
            return
            
        # Downscale OpenCV frame for QPixmap representation
        h, w, c = frame.shape
        target_h = 170
        target_w = int((w / h) * target_h)
        resized = cv2.resize(frame, (target_w, target_h))
        
        # Convert BGR to RGB
        rgb_image = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
        
        # Draw into QImage
        q_img = QImage(rgb_image.data, target_w, target_h, target_w * 3, QImage.Format.Format_RGB888)
        self.video_lbl.setPixmap(QPixmap.fromImage(q_img))

    # --- Slider Sensitivity Handlers ---
    def update_yaw_threshold(self, val):
        self.yaw_val_lbl.setText(f"{val}°")
        database.save_setting("yaw_threshold", val)

    def update_pitch_threshold(self, val):
        self.pitch_val_lbl.setText(f"{val}°")
        database.save_setting("pitch_threshold", val)

    def update_eye_threshold(self, val):
        self.eye_val_lbl.setText(f"{val}%")
        database.save_setting("eye_roll_threshold", val)

    def update_warning_delay(self, val):
        self.delay_val_lbl.setText(f"{val}s")
        database.save_setting("warning_delay", val)

    # --- Blacklist Controllers ---
    def load_blacklist_data(self):
        saved = database.get_setting("blacklist_keywords", "facebook,instagram,twitter,reddit,tiktok")
        self.blacklist_items = [k.strip() for k in saved.split(",") if k.strip()]
        self.update_blacklist_listbox()

    def update_blacklist_listbox(self):
        self.blacklist_widget.clear()
        for item in self.blacklist_items:
            list_item = QListWidgetItem(item)
            list_item.setForeground(QColor("#f43f5e"))
            self.blacklist_widget.addItem(list_item)
        self.blacklist_updated.emit()

    def add_blacklist_item(self):
        text = self.txt_add.text().strip().lower()
        if text and text not in self.blacklist_items:
            if "youtube" in text:
                return # strictly whitelist youtube
            self.blacklist_items.append(text)
            database.save_setting("blacklist_keywords", ",".join(self.blacklist_items))
            self.update_blacklist_listbox()
            self.txt_add.clear()

    # Listbox double click removal helper
    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Delete and self.blacklist_widget.hasFocus():
            selected = self.blacklist_widget.selectedItems()
            if selected:
                val = selected[0].text()
                self.blacklist_items.remove(val)
                database.save_setting("blacklist_keywords", ",".join(self.blacklist_items))
                self.update_blacklist_listbox()
        else:
            super().keyPressEvent(event)

    # --- Pomodoro UI states ---
    def toggle_pomodoro(self):
        self.pomodoro_active = not self.pomodoro_active
        self.pomodoro_toggled.emit(self.pomodoro_active)
        
        if self.pomodoro_active:
            self.pomo_btn.setText("⏹️ Stop Study Session")
            self.pomo_btn.setStyleSheet("""
                QPushButton {
                    background-color: rgba(244, 63, 94, 0.12);
                    color: #f43f5e;
                    border: 1px solid rgba(244, 63, 94, 0.25);
                    font-size: 13px;
                    font-weight: bold;
                }
                QPushButton:hover {
                    background-color: rgba(244, 63, 94, 0.22);
                }
            """)
        else:
            self.pomo_btn.setText("🍅 Start Pomodoro Session")
            self.pomo_btn.setStyleSheet("""
                QPushButton {
                    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #6366f1, stop:1 #8b5cf6);
                    color: white;
                    font-size: 13px;
                    font-weight: bold;
                }
                QPushButton:hover {
                    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #4f46e5, stop:1 #7c3aed);
                }
            """)
            self.timer_lbl.setText("50:00")
            self.phase_lbl.setText("FOCUS PHASE")

    def update_timer_label(self, seconds_left, phase):
        self.study_time_left = seconds_left
        self.pomodoro_phase = phase
        min_part = seconds_left // 60
        sec_part = seconds_left % 60
        self.timer_lbl.setText(f"{min_part:02d}:{sec_part:02d}")
        self.phase_lbl.setText(f"{phase} PHASE")
        self.phase_lbl.setStyleSheet("color: #6366f1; font-weight: 800; letter-spacing: 1.5px;" if phase == "FOCUS" else "color: #94a3b8; font-weight: 800; letter-spacing: 1.5px;")

    # --- Journal Statistics drawing ---
    def update_journal_metrics(self):
        if self.current_filter_mode == "DAY":
            history = database.get_daily_hourly_history()
        elif self.current_filter_mode == "MONTH":
            history = database.get_monthly_history()
        else:
            history = database.get_7_day_history()
        
        total_active = 0
        total_distracted = 0
        for entry in history:
            total_active += entry["active_seconds"]
            total_distracted += entry["distracted_seconds"]
            
        active_min = round(total_active / 60)
        distracted_min = round(total_distracted / 60)
        efficiency = round((total_active / (total_active + total_distracted)) * 100) if (total_active + total_distracted) > 0 else 100

        self.lbl_j_active.setText(f"{active_min}m")
        self.lbl_j_distracted.setText(f"{distracted_min}m")
        self.lbl_j_ratio.setText(f"{efficiency}%")

        # Rebuild visual layout bars
        # Clear layout
        while self.chart_layout.count():
            item = self.chart_layout.takeAt(0)
            widget = item.widget()
            if widget:
                widget.deleteLater()

        if not history:
            empty_lbl = QLabel("Start studying to generate journal stats", self.chart_frame)
            empty_lbl.setFont(QFont("Inter", 9))
            empty_lbl.setStyleSheet("color: #475569;")
            empty_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.chart_layout.addWidget(empty_lbl)
            return

        max_val = 1
        for e in history:
            s = e["active_seconds"] + e["distracted_seconds"]
            if s > max_val: max_val = s

        for entry in history:
            bar_col = QFrame(self.chart_frame)
            bar_col.setFixedWidth(42)
            bar_col.setStyleSheet("background: transparent;")
            
            col_layout = QVBoxLayout(bar_col)
            col_layout.setContentsMargins(0, 0, 0, 0)
            col_layout.setSpacing(3)
            col_layout.setAlignment(Qt.AlignmentFlag.AlignBottom)

            # Draw visual heights (Max height is 110px now!)
            act_h = max(2, int((entry["active_seconds"] / max_val) * 110))
            dist_h = max(2, int((entry["distracted_seconds"] / max_val) * 110))

            f_dist = QFrame(bar_col)
            f_dist.setFixedHeight(dist_h)
            f_dist.setFixedWidth(24)
            f_dist.setStyleSheet("background-color: #f43f5e; border-radius: 3px;")
            f_dist.setToolTip(f"Distracted: {round(entry['distracted_seconds']/60)}m")
            
            f_act = QFrame(bar_col)
            f_act.setFixedHeight(act_h)
            f_act.setFixedWidth(24)
            f_act.setStyleSheet("background-color: #6366f1; border-radius: 3px;")
            f_act.setToolTip(f"Focused: {round(entry['active_seconds']/60)}m")

            col_layout.addWidget(f_dist, 0, Qt.AlignmentFlag.AlignHCenter)
            col_layout.addWidget(f_act, 0, Qt.AlignmentFlag.AlignHCenter)
            
            day_label = QLabel(entry["day"], bar_col)
            day_label.setFont(QFont("Segoe UI", 9, QFont.Weight.Bold))
            day_label.setStyleSheet("color: #64748b; font-weight: bold;")
            day_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
            col_layout.addWidget(day_label)
            
            self.chart_layout.addWidget(bar_col)

    # --- Windows Startup manager toggle ---
    def toggle_startup_preference(self, state):
        enabled = (state == 2)
        database.save_setting("startup_enabled", enabled)
        
        import win32api
        import win32con
        
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        app_name = "GazeReaderStudyCompanion"
        script_path = os.path.abspath(sys.argv[0])
        run_cmd = f'"{sys.executable}" "{script_path}" --silent'

        try:
            key = win32api.RegOpenKeyEx(win32con.HKEY_CURRENT_USER, key_path, 0, win32con.KEY_ALL_ACCESS)
            if enabled:
                win32api.RegSetValueEx(key, app_name, 0, win32con.REG_SZ, run_cmd)
            else:
                try:
                    win32api.RegDeleteValue(key, app_name)
                except FileNotFoundError:
                    pass
            win32api.RegCloseKey(key)
        except Exception as e:
            print("Windows Startup registry updates failed:", e)

    def set_tracker_status(self, colorClass, status_text):
        self.status_lbl.setText(status_text)
        
        # Color codes
        color_hex = "#10b981" # green
        if colorClass == "yellow": color_hex = "#eab308"
        elif colorClass == "red": color_hex = "#f43f5e"
        
        self.dot.setStyleSheet(f"background-color: {color_hex}; border-radius: 5px;")

    def set_phone_status(self, state, msg=""):
        if state == "connected":
            self.phone_dot.setStyleSheet("background-color: #10b981; border-radius: 5px;")
            self.phone_lbl.setText(f"Phone Link: Connected ({msg})" if msg else "Phone Link: Connected")
            self.phone_lbl.setStyleSheet("color: #f8fafc;")
        elif state == "connecting":
            self.phone_dot.setStyleSheet("background-color: #eab308; border-radius: 5px;")
            self.phone_lbl.setText(f"Phone Link: Connecting ({msg})..." if msg else "Phone Link: Connecting...")
            self.phone_lbl.setStyleSheet("color: #cbd5e1;")
        else:
            self.phone_dot.setStyleSheet("background-color: #ef4444; border-radius: 5px;")
            self.phone_lbl.setText(f"Phone Link: Offline ({msg})" if msg else "Phone Link: Offline")
            self.phone_lbl.setStyleSheet("color: #94a3b8;")

    def closeEvent(self, event):
        event.ignore()
        self.hide()

    def register_global_hotkey(self):
        try:
            hwnd = int(self.winId())
            
            # Specify Win32 function signatures explicitly for 64-bit parameter mapping
            ctypes.windll.user32.RegisterHotKey.argtypes = [wintypes.HWND, ctypes.c_int, wintypes.UINT, wintypes.UINT]
            ctypes.windll.user32.RegisterHotKey.restype = wintypes.BOOL

            ctypes.windll.user32.UnregisterHotKey.argtypes = [wintypes.HWND, ctypes.c_int]
            ctypes.windll.user32.UnregisterHotKey.restype = wintypes.BOOL
            
            # MOD_ALT = 0x0001, MOD_CONTROL = 0x0002. Key 0x50 = 'P', Key 0x41 = 'A'
            ctypes.windll.user32.RegisterHotKey(hwnd, 99, 0x0001 | 0x0002, 0x50)
            ctypes.windll.user32.RegisterHotKey(hwnd, 100, 0x0001 | 0x0002, 0x41)
            
            from PyQt6.QtCore import QCoreApplication
            QCoreApplication.instance().aboutToQuit.connect(self.unregister_global_hotkey)
        except Exception as e:
            print("Failed to register global hotkeys Alt+Ctrl+P/A:", e)

    def unregister_global_hotkey(self):
        try:
            hwnd = int(self.winId())
            ctypes.windll.user32.UnregisterHotKey(hwnd, 99)
            ctypes.windll.user32.UnregisterHotKey(hwnd, 100)
        except Exception:
            pass

    def nativeEvent(self, event_type, message):
        if event_type == b"windows_generic_MSG":
            try:
                # Cast the void pointer address safely to retrieve the MSG struct contents
                msg_ptr = ctypes.cast(int(message), ctypes.POINTER(ctypes.wintypes.MSG))
                msg = msg_ptr.contents
                if msg.message == 0x0312 and msg.wParam == 99:
                    self.toggle_pomodoro()
                    return True, 0
                elif msg.message == 0x0312 and msg.wParam == 100:
                    self.adapt_hotkey_pressed.emit()
                    return True, 0
                elif msg.message == 0x0218 and msg.wParam == 0x0012:
                    # WM_POWERBROADCAST and PBT_APMRESUMESUSPEND (system resuming from sleep/hibernate)
                    self.resume_suspend_detected.emit()
            except Exception as e:
                print("Error parsing native message:", e)
        return False, 0

    def change_filter_mode(self, mode):
        self.current_filter_mode = mode
        
        # Reset properties
        self.btn_filter_day.setProperty("active", "false")
        self.btn_filter_week.setProperty("active", "false")
        self.btn_filter_month.setProperty("active", "false")
        
        if mode == "DAY":
            self.btn_filter_day.setProperty("active", "true")
        elif mode == "WEEK":
            self.btn_filter_week.setProperty("active", "true")
        elif mode == "MONTH":
            self.btn_filter_month.setProperty("active", "true")
            
        # Refresh stylesheets to apply active state changes
        self.btn_filter_day.setStyle(self.btn_filter_day.style())
        self.btn_filter_week.setStyle(self.btn_filter_week.style())
        self.btn_filter_month.setStyle(self.btn_filter_month.style())
        
        # Reload charts
        self.update_journal_metrics()
