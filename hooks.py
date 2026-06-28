import ctypes
from ctypes import Structure, windll, c_uint, sizeof, byref
import win32gui
import win32process
import psutil

# --- Windows GetLastInputInfo Structure for Idle Tracking ---
class LASTINPUTINFO(Structure):
    _fields_ = [
        ("cbSize", c_uint),
        ("dwTime", c_uint)
    ]

def get_last_input_tick():
    """Returns the system tick count of the last keyboard or mouse input."""
    lii = LASTINPUTINFO()
    lii.cbSize = sizeof(LASTINPUTINFO)
    if windll.user32.GetLastInputInfo(byref(lii)):
        return lii.dwTime
    return 0

def get_system_idle_time():
    """Returns the time in seconds since the last user input occurred system-wide."""
    current_tick = windll.kernel32.GetTickCount()
    last_input_tick = get_last_input_tick()
    
    # Tick counts wrap around every 49.7 days, handle difference safely
    diff = current_tick - last_input_tick
    if diff < 0:
        diff = 0
    return diff / 1000.0

# --- Foreground Window and Process Resolver ---
def get_foreground_process_details():
    """
    Queries the OS for the active foreground window.
    Returns:
        tuple: (process_name_lowercase, window_title) or (None, None)
    """
    hwnd = win32gui.GetForegroundWindow()
    if not hwnd:
        return None, None

    # Get active window title text
    title = win32gui.GetWindowText(hwnd)

    try:
        # Resolve process ID (PID) from window handle
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        if pid <= 0:
            return None, title
            
        # Extract process image name using psutil
        proc = psutil.Process(pid)
        proc_name = proc.name().lower()
        return proc_name, title
    except Exception:
        # Process might have terminated, or handle permissions denied
        return None, title

# --- Distraction Blacklist Check ---
def check_is_distracted_active(blacklist_keywords):
    """
    Evaluates if the current active process/window matches blacklisted criteria.
    Args:
        blacklist_keywords (list): List of strings (e.g. ['facebook', 'reddit'])
    Returns:
        tuple: (is_distracted, detail_reason)
    """
    proc_name, title = get_foreground_process_details()
    if not proc_name:
        return False, "No active window"
        
    proc_name = proc_name.lower()
    title_lower = title.lower() if title else ""

    # Verify if active window is a web browser (Chrome, Edge, Firefox, Brave)
    browser_processes = ["chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe"]
    
    if proc_name in browser_processes:
        # If it's a browser, check if title matches blacklisted keywords (e.g., 'facebook')
        for keyword in blacklist_keywords:
            keyword = keyword.lower().strip()
            if not keyword:
                continue
                
            # Strict whitelist exception for educational YouTube lectures
            if "youtube" in keyword:
                continue # ignore youtube keyword blocks entirely
                
            if keyword in title_lower:
                # Confirm YouTube is not open in that browser tab before blocking
                if "youtube" in title_lower:
                    return False, "Allowed educational media (YouTube)"
                return True, f"Blocked Website inside {proc_name} ({keyword})"
    else:
        # For non-browser desktop processes (e.g. discord.exe or steam.exe)
        for keyword in blacklist_keywords:
            keyword = keyword.lower().strip()
            if not keyword:
                continue
            if keyword in proc_name or (title_lower and keyword in title_lower):
                return True, f"Blocked Process: {proc_name}"

    return False, "On-task"

# Quick test script
if __name__ == "__main__":
    import time
    print("Starting process tracking test (press Ctrl+C to stop)...")
    try:
        while True:
            proc, title = get_foreground_process_details()
            idle = get_system_idle_time()
            print(f"Active App: {proc} | Title: {title} | Idle Time: {idle:.2f}s")
            time.sleep(1)
    except KeyboardInterrupt:
        print("Test stopped.")
