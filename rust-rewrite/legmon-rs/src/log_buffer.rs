use crate::models::LogEntry;
use chrono::Utc;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

#[derive(Debug)]
pub struct LogBufferState {
    pub logs: VecDeque<LogEntry>,
    pub error_count: usize,
    pub last_error: String,
    pub has_error: bool,
}

#[derive(Clone)]
pub struct LogBuffer {
    state: Arc<Mutex<LogBufferState>>,
}

impl LogBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            state: Arc::new(Mutex::new(LogBufferState {
                logs: VecDeque::with_capacity(capacity),
                error_count: 0,
                last_error: String::new(),
                has_error: false,
            })),
        }
    }

    pub fn append(&self, level: &str, msg: &str) {
        let entry = LogEntry {
            time: Utc::now().to_rfc3339(),
            level: level.to_string(),
            msg: msg.to_string(),
        };

        let mut lock = self.state.lock().unwrap();
        if lock.logs.len() >= 200 {
            lock.logs.pop_front();
        }

        if level == "ERROR" {
            lock.has_error = true;
            lock.error_count += 1;
            lock.last_error = msg.to_string();
        }

        lock.logs.push_back(entry);
    }

    pub fn info(&self, msg: &str) {
        self.append("INFO", msg);
    }

    pub fn warn(&self, msg: &str) {
        self.append("WARN", msg);
    }

    pub fn error(&self, msg: &str) {
        self.append("ERROR", msg);
    }

    pub fn clear_errors(&self) {
        let mut lock = self.state.lock().unwrap();
        lock.has_error = false;
        lock.error_count = 0;
        lock.last_error.clear();
    }

    pub fn get_recent(&self, limit: usize) -> (bool, usize, String, Vec<LogEntry>) {
        let lock = self.state.lock().unwrap();
        let logs: Vec<LogEntry> = lock.logs.iter().rev().take(limit).cloned().collect();
        let logs: Vec<LogEntry> = logs.into_iter().rev().collect();
        (lock.has_error, lock.error_count, lock.last_error.clone(), logs)
    }

    pub fn get_status(&self) -> (bool, usize, String, usize) {
        let lock = self.state.lock().unwrap();
        (lock.has_error, lock.error_count, lock.last_error.clone(), lock.logs.len())
    }
}
