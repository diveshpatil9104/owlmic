//! The shape every Owlmic hub shares (SYSTEM_DESIGN section 11): one thread per hub with a
//! bounded mailbox, modules with one job each under supervision, health reported upward, and an
//! immutable state snapshot for the UI.

mod mailbox;
mod publish;
mod supervise;

pub use mailbox::{Inbox, Outbox, mailbox};
pub use publish::{Publisher, Snapshot};
pub use supervise::{Module, Supervised};

use std::time::Instant;

/// What a module or hub reports about itself once a second.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum Health {
    #[default]
    Ok,
    Degraded(String),
    Failed(String),
}

impl Health {
    /// The worse of two reports, so a hub can fold its modules into one.
    pub fn worst(self, other: Health) -> Health {
        match (self, other) {
            (Health::Failed(r), _) | (_, Health::Failed(r)) => Health::Failed(r),
            (Health::Degraded(r), _) | (_, Health::Degraded(r)) => Health::Degraded(r),
            _ => Health::Ok,
        }
    }
}

/// A hub's logic. The runner owns the thread: it blocks on the mailbox until a message arrives or
/// the hub's next deadline passes, so an idle hub costs nothing.
pub trait Hub: Send + 'static {
    type Msg: Send + 'static;

    fn handle(&mut self, msg: Self::Msg);

    /// Timers: called when [`Hub::next_deadline`] has passed.
    fn tick(&mut self, _now: Instant) {}

    /// When the hub next needs [`Hub::tick`], or `None` to sleep until a message.
    fn next_deadline(&self) -> Option<Instant> {
        None
    }
}

/// Runs `hub` on its own named thread until every [`Outbox`] for `inbox` is gone.
pub fn spawn<H: Hub>(name: &str, mut hub: H, inbox: Inbox<H::Msg>) -> std::thread::JoinHandle<()> {
    std::thread::Builder::new()
        .name(name.to_owned())
        .spawn(move || {
            loop {
                match inbox.recv_until(hub.next_deadline()) {
                    mailbox::Recv::Msg(m) => hub.handle(m),
                    mailbox::Recv::Deadline => {}
                    mailbox::Recv::Closed => return,
                }
                let now = Instant::now();
                if hub.next_deadline().is_some_and(|d| d <= now) {
                    hub.tick(now);
                }
            }
        })
        .expect("spawning a hub thread")
}

struct HubModule<H>(H);

impl<H: Hub> Module for HubModule<H> {
    type Command = H::Msg;

    fn name(&self) -> &'static str {
        "hub"
    }

    fn handle(&mut self, command: H::Msg) {
        self.0.handle(command);
    }
}

/// Like [`spawn`], but a hub that panics is rebuilt with `make` after a backoff (SYSTEM_DESIGN
/// section 11.3). The hub is built on its own thread, so thread-bound resources stay there.
pub fn spawn_supervised<H: Hub>(
    name: &str,
    mut make: impl FnMut() -> H + Send + 'static,
    inbox: Inbox<H::Msg>,
) -> std::thread::JoinHandle<()> {
    std::thread::Builder::new()
        .name(name.to_owned())
        .spawn(move || {
            let mut hub = Supervised::new(move || HubModule(make()));
            loop {
                let now = Instant::now();
                let own = hub.with(now, |h| h.0.next_deadline()).flatten();
                let deadline = [hub.next_deadline(), own].into_iter().flatten().min();
                match inbox.recv_until(deadline) {
                    mailbox::Recv::Msg(m) => hub.handle(m, Instant::now()),
                    mailbox::Recv::Deadline => {}
                    mailbox::Recv::Closed => return,
                }
                let now = Instant::now();
                hub.tick(now);
                if hub
                    .with(now, |h| h.0.next_deadline())
                    .flatten()
                    .is_some_and(|d| d <= now)
                {
                    hub.with(now, |h| h.0.tick(now));
                }
            }
        })
        .expect("spawning a hub thread")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::Duration;

    struct Fragile(mpsc::Sender<u32>);

    impl Hub for Fragile {
        type Msg = u32;
        fn handle(&mut self, msg: u32) {
            assert!(msg != 0, "boom");
            self.0.send(msg).unwrap();
        }
    }

    #[test]
    fn a_hub_that_panics_is_rebuilt() {
        let (tx, rx) = mpsc::channel();
        let built = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let b = built.clone();
        let (out, inbox) = mailbox(8);
        let thread = spawn_supervised(
            "fragile",
            move || {
                b.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Fragile(tx.clone())
            },
            inbox,
        );
        out.send(1);
        assert_eq!(rx.recv_timeout(Duration::from_secs(2)), Ok(1));
        let hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        out.send(0);
        std::thread::sleep(Duration::from_millis(300));
        std::panic::set_hook(hook);
        out.send(2);
        assert_eq!(rx.recv_timeout(Duration::from_secs(2)), Ok(2));
        assert_eq!(built.load(std::sync::atomic::Ordering::SeqCst), 2);
        drop(out);
        thread.join().unwrap();
    }

    #[test]
    fn worst_health_wins() {
        assert_eq!(Health::Ok.worst(Health::Ok), Health::Ok);
        assert_eq!(
            Health::Ok.worst(Health::Degraded("a".into())),
            Health::Degraded("a".into())
        );
        assert_eq!(
            Health::Degraded("a".into()).worst(Health::Failed("b".into())),
            Health::Failed("b".into())
        );
    }

    struct Echo {
        out: mpsc::Sender<u32>,
        deadline: Option<Instant>,
    }

    impl Hub for Echo {
        type Msg = u32;
        fn handle(&mut self, msg: u32) {
            self.out.send(msg).unwrap();
            if msg == 7 {
                self.deadline = Some(Instant::now() + Duration::from_millis(20));
            }
        }
        fn tick(&mut self, _now: Instant) {
            self.deadline = None;
            self.out.send(1000).unwrap();
        }
        fn next_deadline(&self) -> Option<Instant> {
            self.deadline
        }
    }

    #[test]
    fn a_hub_handles_messages_and_timers_then_stops_when_its_senders_go() {
        let (tx, rx) = mpsc::channel();
        let (outbox, inbox) = mailbox(4);
        let thread = spawn(
            "echo",
            Echo {
                out: tx,
                deadline: None,
            },
            inbox,
        );
        outbox.send(3);
        outbox.send(7);
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)), Ok(3));
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)), Ok(7));
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)), Ok(1000));
        drop(outbox);
        thread.join().unwrap();
    }
}
