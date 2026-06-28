import sqlite3
import os
import time

DB_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gaze_study_journal.db")

def get_db_connection():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # 1. Create table for study sessions
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS study_sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        day TEXT NOT NULL,
        active_seconds INTEGER DEFAULT 0,
        distracted_seconds INTEGER DEFAULT 0,
        timestamp INTEGER NOT NULL
    )
    """)
    
    # 2. Create table for distraction logs
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS distraction_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        domain_or_app TEXT NOT NULL,
        duration_seconds INTEGER DEFAULT 0,
        timestamp INTEGER NOT NULL
    )
    """)
    
    # 3. Create table for app configurations
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    )
    """)
    
    # Insert sensitive default parameters if missing or set to old defaults
    cursor.execute("SELECT value FROM settings WHERE key = 'yaw_threshold'")
    row = cursor.fetchone()
    if not row or row[0] == "26":
        cursor.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('yaw_threshold', '18')")
        
    cursor.execute("SELECT value FROM settings WHERE key = 'pitch_threshold'")
    row = cursor.fetchone()
    if not row or row[0] == "22":
        cursor.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('pitch_threshold', '14')")
        
    cursor.execute("SELECT value FROM settings WHERE key = 'warning_delay'")
    row = cursor.fetchone()
    if not row or row[0] == "5":
        cursor.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('warning_delay', '4')")
        
    cursor.execute("SELECT value FROM settings WHERE key = 'eye_roll_threshold'")
    row = cursor.fetchone()
    if not row:
        cursor.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('eye_roll_threshold', '35')")
        
    conn.commit()
    conn.close()

def save_session(day, active, distracted):
    if active == 0 and distracted == 0:
        return
    conn = get_db_connection()
    cursor = conn.cursor()
    
    now = int(time.time())
    # Check if we already logged a session today (within last 12 hours) to accumulate
    twelve_hours_ago = now - (12 * 3600)
    cursor.execute("""
        SELECT id, active_seconds, distracted_seconds FROM study_sessions 
        WHERE day = ? AND timestamp > ? ORDER BY id DESC LIMIT 1
    """, (day, twelve_hours_ago))
    row = cursor.fetchone()
    
    if row:
        new_active = row["active_seconds"] + active
        new_distracted = row["distracted_seconds"] + distracted
        cursor.execute("""
            UPDATE study_sessions SET active_seconds = ?, distracted_seconds = ?, timestamp = ?
            WHERE id = ?
        """, (new_active, new_distracted, now, row["id"]))
    else:
        cursor.execute("""
            INSERT INTO study_sessions (day, active_seconds, distracted_seconds, timestamp)
            VALUES (?, ?, ?, ?)
        """, (day, active, distracted, now))
        
    conn.commit()
    conn.close()

def log_distraction(domain_or_app, duration):
    if duration <= 0:
        return
    conn = get_db_connection()
    cursor = conn.cursor()
    
    now = int(time.time())
    twelve_hours_ago = now - (12 * 3600)
    
    # Check if already logged today to accumulate
    cursor.execute("""
        SELECT id, duration_seconds FROM distraction_logs
        WHERE domain_or_app = ? AND timestamp > ? ORDER BY id DESC LIMIT 1
    """, (domain_or_app, twelve_hours_ago))
    row = cursor.fetchone()
    
    if row:
        new_duration = row["duration_seconds"] + duration
        cursor.execute("""
            UPDATE distraction_logs SET duration_seconds = ?, timestamp = ?
            WHERE id = ?
        """, (new_duration, now, row["id"]))
    else:
        cursor.execute("""
            INSERT INTO distraction_logs (domain_or_app, duration_seconds, timestamp)
            VALUES (?, ?, ?)
        """, (domain_or_app, duration, now))
        
    conn.commit()
    conn.close()

def get_7_day_history():
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # Fetch last 7 rows ordered by timestamp
    cursor.execute("""
        SELECT day, active_seconds, distracted_seconds FROM study_sessions
        ORDER BY timestamp DESC LIMIT 7
    """)
    rows = cursor.fetchall()
    conn.close()
    
    # Reverse to show in correct chronological order (Mon -> Sun)
    return list(reversed([dict(r) for r in rows]))

def get_top_distractions():
    conn = get_db_connection()
    cursor = conn.cursor()
    
    cursor.execute("""
        SELECT domain_or_app, SUM(duration_seconds) as total_seconds FROM distraction_logs
        GROUP BY domain_or_app
        ORDER BY total_seconds DESC LIMIT 5
    """)
    rows = cursor.fetchall()
    conn.close()
    
    return [dict(r) for r in rows]

def save_setting(key, value):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO settings (key, value)
        VALUES (?, ?)
    """, (key, str(value)))
    conn.commit()
    conn.close()

def get_setting(key, default):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT value FROM settings WHERE key = ?", (key,))
    row = cursor.fetchone()
    conn.close()
    
    if row:
        val = row["value"]
        # Convert types if necessary
        if val.lower() == "true": return True
        if val.lower() == "false": return False
        try:
            return int(val)
        except ValueError:
            try:
                return float(val)
            except ValueError:
                return val
    return default

def get_daily_hourly_history():
    conn = get_db_connection()
    cursor = conn.cursor()
    one_day_ago = int(time.time()) - 24 * 3600
    cursor.execute("""
        SELECT 
            strftime('%H', datetime(timestamp, 'unixepoch', 'localtime')) as time_key,
            SUM(active_seconds) as active_seconds,
            SUM(distracted_seconds) as distracted_seconds
        FROM study_sessions
        WHERE timestamp > ?
        GROUP BY time_key
        ORDER BY time_key ASC
    """, (one_day_ago,))
    rows = cursor.fetchall()
    conn.close()
    
    res = []
    for r in rows:
        d = dict(r)
        d["day"] = f"{d['time_key']}h"
        res.append(d)
    return res

def get_monthly_history():
    conn = get_db_connection()
    cursor = conn.cursor()
    thirty_days_ago = int(time.time()) - 30 * 24 * 3600
    cursor.execute("""
        SELECT 
            strftime('%m-%d', datetime(timestamp, 'unixepoch', 'localtime')) as day,
            SUM(active_seconds) as active_seconds,
            SUM(distracted_seconds) as distracted_seconds
        FROM study_sessions
        WHERE timestamp > ?
        GROUP BY day
        ORDER BY timestamp ASC LIMIT 30
    """, (thirty_days_ago,))
    rows = cursor.fetchall()
    conn.close()
    return [dict(r) for r in rows]

# Initialize database on module load
init_db()
