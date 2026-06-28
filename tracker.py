import cv2
import mediapipe as mp
import numpy as np
import math
import time
import os
import database
from PyQt6.QtCore import QThread, pyqtSignal

class FaceGazeTracker(QThread):
    # Signals to communicate with the main application thread
    status_updated = pyqtSignal(str, str) # (colorClass, textStatus)
    gaze_data_updated = pyqtSignal(float, float, float, bool, bool) # (yaw, pitch, roll, is_face, is_eye_distracted)
    frame_ready = pyqtSignal(np.ndarray) # For optional camera dashboard preview

    def __init__(self):
        super().__init__()
        self.running = False
        self.camera_index = 0
        self.mp_face_mesh = mp.solutions.face_mesh
        
        # 3D Generic Head Model Reference Coordinates (in mm)
        self.model_points = np.array([
            (0.0, 0.0, 0.0),             # Nose tip
            (0.0, -330.0, -65.0),        # Chin
            (-225.0, 170.0, -135.0),     # Left eye outer corner
            (225.0, 170.0, -135.0),      # Right eye outer corner
            (-150.0, -150.0, -125.0),    # Left mouth corner
            (150.0, -150.0, -125.0)      # Right mouth corner
        ], dtype="double")

    def run(self):
        self.running = True
        
        # Initialize OpenCV Video Capture
        self.status_updated.emit("yellow", "Starting camera sensor...")
        cap = cv2.VideoCapture(self.camera_index, cv2.CAP_DSHOW)
        if not cap.isOpened():
            cap = cv2.VideoCapture(self.camera_index)
            
        if not cap.isOpened():
            self.status_updated.emit("red", "Webcam blocked / missing")
            self.gaze_data_updated.emit(0.0, 0.0, 0.0, False, False)
            self.running = False
            return

        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_FPS, 30)

        # Initialize FaceMesh
        self.status_updated.emit("yellow", "Compiling Face Mesh model...")
        with self.mp_face_mesh.FaceMesh(
            max_num_faces=1,
            refine_landmarks=True,
            min_detection_confidence=0.6,
            min_tracking_confidence=0.6
        ) as face_mesh:
            
            self.status_updated.emit("green", "Tracking Active")
            last_frame_time = time.time()
            
            while self.running:
                ret, frame = cap.read()
                if not ret:
                    self.status_updated.emit("red", "Camera feed lost. Reconnecting...")
                    cap.release()
                    time.sleep(2.0)
                    cap = cv2.VideoCapture(self.camera_index, cv2.CAP_DSHOW)
                    if not cap.isOpened():
                        cap = cv2.VideoCapture(self.camera_index)
                    continue

                frame = cv2.flip(frame, 1)
                h, w, c = frame.shape
                
                rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                results = face_mesh.process(rgb_frame)

                is_face_present = False
                yaw, pitch, roll = 0.0, 0.0, 0.0
                is_eye_distracted = False

                # A. Evaluate FaceMesh landmarks for head pose
                if results.multi_face_landmarks:
                    is_face_present = True
                    face_landmarks = results.multi_face_landmarks[0]
                    
                    image_points = np.array([
                        (face_landmarks.landmark[1].x * w, face_landmarks.landmark[1].y * h),       # Nose tip
                        (face_landmarks.landmark[152].x * w, face_landmarks.landmark[152].y * h),   # Chin
                        (face_landmarks.landmark[263].x * w, face_landmarks.landmark[263].y * h),   # Left eye outer
                        (face_landmarks.landmark[33].x * w, face_landmarks.landmark[33].y * h),     # Right eye outer
                        (face_landmarks.landmark[287].x * w, face_landmarks.landmark[287].y * h),   # Left mouth corner
                        (face_landmarks.landmark[57].x * w, face_landmarks.landmark[57].y * h)      # Right mouth corner
                    ], dtype="double")

                    focal_length = w
                    center = (w / 2, h / 2)
                    camera_matrix = np.array([
                        [focal_length, 0, center[0]],
                        [0, focal_length, center[1]],
                        [0, 0, 1]
                    ], dtype="double")

                    dist_coeffs = np.zeros((4, 1))

                    success, rotation_vector, translation_vector = cv2.solvePnP(
                        self.model_points, image_points, camera_matrix, dist_coeffs, flags=cv2.SOLVEPNP_ITERATIVE
                    )

                    if success:
                        rmat, _ = cv2.Rodrigues(rotation_vector)
                        
                        sy = math.sqrt(rmat[0, 0] * rmat[0, 0] + rmat[1, 0] * rmat[1, 0])
                        singular = sy < 1e-6
                        
                        if not singular:
                            x = math.atan2(rmat[2, 1], rmat[2, 2])
                            y = math.atan2(-rmat[2, 0], sy)
                            z = math.atan2(rmat[1, 0], rmat[0, 0])
                        else:
                            x = math.atan2(-rmat[1, 2], rmat[1, 1])
                            y = math.atan2(-rmat[2, 0], sy)
                            z = 0

                        pitch = x * 180.0 / math.pi
                        yaw = y * 180.0 / math.pi
                        roll = z * 180.0 / math.pi
                        
                        for pt in image_points:
                            cv2.circle(frame, (int(pt[0]), int(pt[1])), 3, (99, 102, 241), -1)
                        
                        (nose_end_point2D, jacobian) = cv2.projectPoints(
                            np.array([(0.0, 0.0, 500.0)]), rotation_vector, translation_vector, camera_matrix, dist_coeffs
                        )
                        p1 = (int(image_points[0][0]), int(image_points[0][1]))
                        p2 = (int(nose_end_point2D[0][0][0]), int(nose_end_point2D[0][0][1]))
                        cv2.line(frame, p1, p2, (244, 63, 94), 2)

                    # B. Iris horizontal glance / eye-rolling tracking calculations
                    try:
                        # Horizontal Iris look-aside (Left corners: 33/133, Right corners: 362/263)
                        left_iris_x = face_landmarks.landmark[468].x
                        left_out_x = face_landmarks.landmark[33].x
                        left_in_x = face_landmarks.landmark[133].x
                        den_hl = left_in_x - left_out_x
                        ratio_hl = (left_iris_x - left_out_x) / den_hl if den_hl > 0.001 else 0.5

                        right_iris_x = face_landmarks.landmark[473].x
                        right_in_x = face_landmarks.landmark[362].x
                        right_out_x = face_landmarks.landmark[263].x
                        den_hr = right_out_x - right_in_x
                        ratio_hr = (right_iris_x - right_in_x) / den_hr if den_hr > 0.001 else 0.5
                        
                        h_ratio = (ratio_hl + ratio_hr) / 2.0

                        # Flag eye distractions if pupil is looking extremely left/right (rolling)
                        eye_limit = float(database.get_setting("eye_roll_threshold", 35)) / 100.0
                        if h_ratio < eye_limit or h_ratio > (1.0 - eye_limit):
                            is_eye_distracted = True
                    except Exception:
                        is_eye_distracted = False

                # Emit tracking data
                self.gaze_data_updated.emit(yaw, pitch, roll, is_face_present, is_eye_distracted)
                self.frame_ready.emit(frame)

                # Maintain steady loop timing (~30 FPS)
                elapsed = time.time() - last_frame_time
                wait_time = max(0.01, 0.033 - elapsed)
                time.sleep(wait_time)
                last_frame_time = time.time()

        cap.release()
        self.status_updated.emit("red", "Standby (Off)")

    def stop(self):
        self.running = False
        self.wait()
