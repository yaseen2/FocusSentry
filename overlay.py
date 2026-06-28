from PyQt6.QtWidgets import QWidget, QLabel, QPushButton, QVBoxLayout, QFrame
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QRect
from PyQt6.QtGui import QPainter, QColor, QPen, QBrush, QFont, QLinearGradient

class DesktopOverlay(QWidget):
    resume_requested = pyqtSignal()

    def __init__(self, screen_geometry):
        super().__init__()
        self.screen_geom = screen_geometry
        self.state = "INACTIVE" # INACTIVE, PRE_WARNING, DISTRACTED
        self.pre_warning_seconds = 5
        self.countdown_reason = "Focus Lost"
        self.pulse_alpha = 100
        self.pulse_growing = True

        # PyQt6 Window setup
        self.setGeometry(self.screen_geom)
        self.set_overlay_flags(transparent=True)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose, False)

        # Pulse timer for amber warning glow
        self.pulse_timer = QTimer(self)
        self.pulse_timer.timeout.connect(self.update_pulse)
        
        # Countdown timer for Stage 2 warning
        self.countdown_timer = QTimer(self)
        self.countdown_timer.timeout.connect(self.countdown_tick)

        self.init_ui()

    def set_overlay_flags(self, transparent=True):
        """Configure Window flags to enable borderless, top-level, and optional click-through states."""
        flags = (
            Qt.WindowType.FramelessWindowHint | 
            Qt.WindowType.WindowStaysOnTopHint | 
            Qt.WindowType.Tool | # Hides window from taskbar
            Qt.WindowType.SubWindow
        )
        if transparent:
            flags |= Qt.WindowType.WindowTransparentForInput
            
        self.setWindowFlags(flags)

    def init_ui(self):
        # We handle layout drawing dynamically via paintEvent, 
        # but we also define the PyQt interactive widgets for the Stage 3 Lock Card
        self.lock_card = QFrame(self)
        self.lock_card.setObjectName("lock_card")
        self.lock_card.setStyleSheet("""
            QFrame#lock_card {
                background-color: rgba(18, 14, 28, 230);
                border: 2px solid rgba(244, 63, 94, 90);
                border-radius: 16px;
            }
        """)
        
        layout = QVBoxLayout(self.lock_card)
        layout.setContentsMargins(30, 30, 30, 30)
        layout.setSpacing(15)
        
        # Lock Warning Icon
        self.icon_lbl = QLabel("⚠️", self.lock_card)
        self.icon_lbl.setFont(QFont("Segoe UI Emoji", 42))
        self.icon_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self.icon_lbl)
        
        # Lock Title
        self.title_lbl = QLabel("Study Session Paused!", self.lock_card)
        self.title_lbl.setFont(QFont("Outfit", 20, QFont.Weight.Bold))
        self.title_lbl.setStyleSheet("color: #f43f5e;")
        self.title_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self.title_lbl)
        
        # Lock description
        self.desc_lbl = QLabel("Please return your focus to GazeReader.", self.lock_card)
        self.desc_lbl.setFont(QFont("Inter", 11))
        self.desc_lbl.setStyleSheet("color: #94a3b8;")
        self.desc_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.desc_lbl.setWordWrap(True)
        layout.addWidget(self.desc_lbl)
        
        # Resume button
        self.resume_btn = QPushButton("Resume Study Session", self.lock_card)
        self.resume_btn.setFont(QFont("Inter", 11, QFont.Weight.Bold))
        self.resume_btn.setStyleSheet("""
            QPushButton {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #6366f1, stop:1 #8b5cf6);
                color: white;
                border: none;
                border-radius: 6px;
                padding: 10px 24px;
            }
            QPushButton:hover {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #4f46e5, stop:1 #7c3aed);
            }
        """)
        self.resume_btn.clicked.connect(self.request_resume)
        layout.addWidget(self.resume_btn)
        
        # Size and position card center
        card_w, card_h = 380, 260
        x = (self.screen_geom.width() - card_w) // 2
        y = (self.screen_geom.height() - card_h) // 2
        self.lock_card.setGeometry(x, y, card_w, card_h)
        self.lock_card.hide()

    def show_pre_warning(self, reason, delay_seconds=5):
        if self.state == "PRE_WARNING" or self.state == "DISTRACTED":
            return
        
        self.state = "PRE_WARNING"
        self.countdown_reason = reason
        self.pre_warning_seconds = delay_seconds
        self.pulse_alpha = 100
        
        # Start timers
        self.pulse_timer.start(30) # ~30 FPS pulses
        self.countdown_timer.start(1000)
        self.lock_card.hide()
        
        # Bring window to foreground but keep it transparent to user inputs
        self.set_overlay_flags(transparent=True)
        self.show()
        self.raise_()

    def show_distracted(self, reason):
        self.state = "DISTRACTED"
        self.pulse_timer.stop()
        self.countdown_timer.stop()
        
        self.desc_lbl.setText(f"Reason: {reason}.\nMove mouse or press resume button to continue.")
        self.lock_card.show()
        
        # Lock keyboard/mouse inputs: make window intercept clicks!
        self.set_overlay_flags(transparent=False)
        self.show()
        self.raise_()
        self.activateWindow()

    def clear_overlay(self):
        self.state = "INACTIVE"
        self.pulse_timer.stop()
        self.countdown_timer.stop()
        self.lock_card.hide()
        self.hide()

    def request_resume(self):
        self.resume_requested.emit()

    def update_pulse(self):
        # Pulse alpha back and forth between 60 and 200
        if self.pulse_growing:
            self.pulse_alpha += 6
            if self.pulse_alpha >= 200:
                self.pulse_alpha = 200
                self.pulse_growing = False
        else:
            self.pulse_alpha -= 6
            if self.pulse_alpha <= 60:
                self.pulse_alpha = 60
                self.pulse_growing = True
        self.update() # Force repaint

    def countdown_tick(self):
        self.pre_warning_seconds -= 1
        if self.pre_warning_seconds <= 0:
            self.countdown_timer.stop()
            # Countdown expired: trigger distraction modal handled by main controller
            # (Main loop handles the state transition to DISTRACTED)
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        
        w = self.width()
        h = self.height()
        
        if self.state == "PRE_WARNING":
            # Check if this is a phone distraction warning
            is_phone = "Phone" in self.countdown_reason
            border_color = QColor(244, 63, 94, self.pulse_alpha) if is_phone else QColor(245, 158, 11, self.pulse_alpha)
            bg_color = QColor(15, 5, 5, 230) if is_phone else QColor(15, 10, 5, 230)
            text_color = QColor(244, 63, 94) if is_phone else QColor(245, 158, 11)
            badge_color = QColor(244, 63, 94) if is_phone else QColor(245, 158, 11)
            
            # 1. Draw glowing screen border pen (thicker red for phone warning)
            pen = QPen(border_color)
            pen.setWidth(12 if is_phone else 8)
            painter.setPen(pen)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawRect(0, 0, w, h)
            
            # 2. Draw Top Floating Banner Box
            banner_w, banner_h = 420, 38
            bx = (w - banner_w) // 2
            by = 0 # sits at top of monitor screen
            
            # Draw banner background
            painter.setPen(QPen(border_color, 1))
            painter.setBrush(QBrush(bg_color))
            painter.drawRoundedRect(bx, by, banner_w, banner_h, 0, 8, Qt.SizeMode.AbsoluteSize)
            
            # Draw Text
            painter.setPen(text_color)
            painter.setFont(QFont("Inter", 10, QFont.Weight.Bold))
            if is_phone:
                banner_text = f"📱 Phone Alert: {self.countdown_reason}... "
            else:
                banner_text = f"🕒 Focus check: {self.countdown_reason}. Wiggle mouse to dismiss... "
            painter.drawText(QRect(bx + 15, by, banner_w - 70, banner_h), Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, banner_text)
            
            # Draw Countdown Badge
            badge_w, badge_h = 24, 20
            badge_x = bx + banner_w - 35
            badge_y = by + (banner_h - badge_h) // 2
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QBrush(badge_color))
            painter.drawRoundedRect(badge_x, badge_y, badge_w, badge_h, 4, 4)
            
            painter.setPen(QColor(11, 17, 30))
            painter.setFont(QFont("Inter", 11, QFont.Weight.Bold))
            painter.drawText(QRect(badge_x, badge_y, badge_w, badge_h), Qt.AlignmentFlag.AlignCenter, str(self.pre_warning_seconds))
            
        elif self.state == "DISTRACTED":
            # 1. Dim the entire screen background with dark tint
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QBrush(QColor(8, 6, 16, 210))) # Dark overlay
            painter.drawRect(0, 0, w, h)
            
            # 2. Add outer red border glow on lock screen
            pen = QPen(QColor(244, 63, 94, 180))
            pen.setWidth(8)
            painter.setPen(pen)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawRect(0, 0, w, h)

    def keyPressEvent(self, event):
        # Allow dismissing lock screen by pressing Spacebar or Enter
        if self.state == "DISTRACTED" and event.key() in (Qt.Key.Key_Space, Qt.Key.Key_Return):
            self.request_resume()
        else:
            super().keyPressEvent(event)
