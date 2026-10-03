use std::time::Duration;

use serde_json::{Value, json};

#[derive(Default)]
pub struct ResourceSampler {
    last: Option<(u64, u64)>,
}

impl ResourceSampler {
    /// Reports the same shape the desktop Worker sends. The first call needs a
    /// second CPU sample, taken 100 ms later, to derive a usage percentage.
    pub async fn sample(&mut self) -> Value {
        let previous = match self.last.take() {
            Some(previous) => previous,
            None => {
                let first = read_cpu();
                tokio::time::sleep(Duration::from_millis(100)).await;
                first
            }
        };
        let current = read_cpu();
        self.last = Some(current);
        let (used, total) = read_memory();
        json!({
            "logical_cpus": logical_cpus(),
            "cpu_usage_percent": cpu_percent(previous, current),
            "memory_used_bytes": used,
            "memory_total_bytes": total,
        })
    }
}

fn read_cpu() -> (u64, u64) {
    let Ok(stat) = std::fs::read_to_string("/proc/stat") else {
        return (0, 0);
    };
    let Some(line) = stat.lines().next() else {
        return (0, 0);
    };
    let values: Vec<u64> = line
        .split_whitespace()
        .skip(1)
        .filter_map(|value| value.parse().ok())
        .collect();
    if values.len() < 4 {
        return (0, 0);
    }
    let idle = values[3] + values.get(4).copied().unwrap_or(0);
    let total: u64 = values.iter().sum();
    (idle, total)
}

fn cpu_percent(previous: (u64, u64), current: (u64, u64)) -> f64 {
    let idle_delta = current.0.saturating_sub(previous.0);
    let total_delta = current.1.saturating_sub(previous.1);
    if total_delta == 0 {
        return 0.0;
    }
    let busy = total_delta.saturating_sub(idle_delta);
    (busy as f64) * 100.0 / (total_delta as f64)
}

fn logical_cpus() -> usize {
    if let Ok(possible) = std::fs::read_to_string("/sys/devices/system/cpu/possible")
        && let Some((_, end)) = possible.trim().split_once('-')
        && let Ok(end) = end.parse::<usize>()
    {
        return end + 1;
    }
    std::thread::available_parallelism()
        .map(|count| count.get())
        .unwrap_or(1)
}

fn read_memory() -> (u64, u64) {
    let Ok(meminfo) = std::fs::read_to_string("/proc/meminfo") else {
        return (0, 0);
    };
    let mut total = 0_u64;
    let mut available = 0_u64;
    for line in meminfo.lines() {
        let mut parts = line.split_whitespace();
        match parts.next() {
            Some("MemTotal:") => {
                total = parts
                    .next()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(0)
            }
            Some("MemAvailable:") => {
                available = parts
                    .next()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(0)
            }
            _ => {}
        }
    }
    let total = total.saturating_mul(1024);
    let available = available.saturating_mul(1024);
    (total.saturating_sub(available), total)
}

#[cfg(test)]
mod tests {
    use super::cpu_percent;

    #[test]
    fn cpu_percentage_is_bounded() {
        assert_eq!(cpu_percent((0, 0), (0, 0)), 0.0);
        let fifty = cpu_percent((100, 200), (150, 300));
        assert!((fifty - 50.0).abs() < f64::EPSILON);
    }
}
