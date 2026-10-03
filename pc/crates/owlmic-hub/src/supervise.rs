//! Supervision: a module that panics is rebuilt after a short backoff, and one that keeps failing
//! is reported as failed instead of taking the app down.

use crate::Health;
use std::collections::VecDeque;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::time::{Duration, Instant};

const BACKOFF: [Duration; 3] = [
    Duration::from_millis(100),
    Duration::from_millis(500),
    Duration::from_secs(2),
];
const MAX_RESTARTS_PER_MINUTE: usize = 5;

/// The contract every module meets (SYSTEM_DESIGN section 11.3).
pub trait Module {
    type Command;

    fn name(&self) -> &'static str;

    /// Acquires resources and begins work.
    fn start(&mut self) -> Result<(), String> {
        Ok(())
    }

    fn handle(&mut self, command: Self::Command);

    fn health(&self) -> Health {
        Health::Ok
    }

    /// Releases everything. Safe to call more than once.
    fn stop(&mut self) {}
}

pub struct Supervised<M: Module> {
    make: Box<dyn FnMut() -> M + Send>,
    module: Option<M>,
    restarts: VecDeque<Instant>,
    restart_at: Option<Instant>,
    escalated: Option<String>,
}

impl<M: Module> Supervised<M> {
    /// Builds and starts the module now.
    pub fn new(make: impl FnMut() -> M + Send + 'static) -> Self {
        let mut s = Self {
            make: Box::new(make),
            module: None,
            restarts: VecDeque::new(),
            restart_at: None,
            escalated: None,
        };
        s.restart(Instant::now());
        s
    }

    /// Hands `command` to the module; dropped while it is down.
    pub fn handle(&mut self, command: M::Command, now: Instant) {
        let Some(module) = self.module.as_mut() else {
            return;
        };
        if catch_unwind(AssertUnwindSafe(|| module.handle(command))).is_err() {
            self.fail(now, "stopped unexpectedly");
        }
    }

    /// Runs `f` with the module when it is up, as a crash-safe call.
    pub fn with<R>(&mut self, now: Instant, f: impl FnOnce(&mut M) -> R) -> Option<R> {
        let module = self.module.as_mut()?;
        match catch_unwind(AssertUnwindSafe(|| f(module))) {
            Ok(r) => Some(r),
            Err(_) => {
                self.fail(now, "stopped unexpectedly");
                None
            }
        }
    }

    /// When a rebuild is due, for the hub's timer.
    pub fn next_deadline(&self) -> Option<Instant> {
        self.restart_at
    }

    pub fn tick(&mut self, now: Instant) {
        if self.restart_at.is_some_and(|t| t <= now) {
            self.restart(now);
        }
    }

    pub fn health(&self) -> Health {
        if let Some(reason) = &self.escalated {
            return Health::Failed(reason.clone());
        }
        match &self.module {
            Some(m) => catch_unwind(AssertUnwindSafe(|| m.health()))
                .unwrap_or(Health::Failed("health check".into())),
            None => Health::Degraded("restarting".into()),
        }
    }

    pub fn is_up(&self) -> bool {
        self.module.is_some()
    }

    fn restart(&mut self, now: Instant) {
        self.restart_at = None;
        let mut module = match catch_unwind(AssertUnwindSafe(|| (self.make)())) {
            Ok(m) => m,
            Err(_) => return self.fail(now, "could not be built"),
        };
        match catch_unwind(AssertUnwindSafe(|| module.start())) {
            Ok(Ok(())) => self.module = Some(module),
            Ok(Err(reason)) => self.fail(now, &reason),
            Err(_) => self.fail(now, "stopped while starting"),
        }
    }

    fn fail(&mut self, now: Instant, reason: &str) {
        if let Some(mut m) = self.module.take() {
            let _ = catch_unwind(AssertUnwindSafe(|| m.stop()));
        }
        while self
            .restarts
            .front()
            .is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60))
        {
            self.restarts.pop_front();
        }
        if self.restarts.len() >= MAX_RESTARTS_PER_MINUTE {
            self.escalated = Some(reason.to_owned());
            return;
        }
        let step = BACKOFF[self.restarts.len().min(BACKOFF.len() - 1)];
        self.restarts.push_back(now);
        self.restart_at = Some(now + step);
    }
}

impl<M: Module> Drop for Supervised<M> {
    fn drop(&mut self) {
        if let Some(mut m) = self.module.take() {
            let _ = catch_unwind(AssertUnwindSafe(|| m.stop()));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Fragile {
        builds: Arc<AtomicUsize>,
    }

    impl Module for Fragile {
        type Command = bool;
        fn name(&self) -> &'static str {
            "fragile"
        }
        fn handle(&mut self, explode: bool) {
            assert!(!explode, "boom");
        }
    }

    fn quiet<R>(f: impl FnOnce() -> R) -> R {
        let hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        let r = f();
        std::panic::set_hook(hook);
        r
    }

    #[test]
    fn a_crashed_module_is_rebuilt_after_backoff() {
        let builds = Arc::new(AtomicUsize::new(0));
        let b = builds.clone();
        let mut s = Supervised::new(move || {
            b.fetch_add(1, Ordering::SeqCst);
            Fragile { builds: b.clone() }
        });
        let t0 = Instant::now();
        quiet(|| s.handle(true, t0));
        assert!(!s.is_up());
        assert_eq!(s.next_deadline(), Some(t0 + Duration::from_millis(100)));
        s.tick(t0 + Duration::from_millis(50));
        assert!(!s.is_up());
        s.tick(t0 + Duration::from_millis(100));
        assert!(s.is_up());
        assert_eq!(builds.load(Ordering::SeqCst), 2);
        assert_eq!(s.health(), Health::Ok);
        let _ = Fragile {
            builds: Arc::new(AtomicUsize::new(0)),
        }
        .builds;
    }

    #[test]
    fn a_module_that_keeps_failing_is_reported_failed() {
        let mut s = Supervised::new(|| Fragile {
            builds: Arc::new(AtomicUsize::new(0)),
        });
        let mut now = Instant::now();
        quiet(|| {
            for _ in 0..6 {
                s.handle(true, now);
                now += Duration::from_secs(3);
                s.tick(now);
            }
        });
        assert!(matches!(s.health(), Health::Failed(_)));
        assert!(!s.is_up());
    }
}
