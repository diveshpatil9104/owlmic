//! A bounded queue that never blocks the sender: when it is full the oldest message goes, since
//! a newer report or command supersedes it.

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Instant;

struct Shared<T> {
    queue: Mutex<State<T>>,
    ready: Condvar,
    capacity: usize,
}

struct State<T> {
    items: VecDeque<T>,
    senders: usize,
    dropped: u64,
}

pub struct Outbox<T> {
    shared: Arc<Shared<T>>,
}

pub struct Inbox<T> {
    shared: Arc<Shared<T>>,
}

pub(crate) enum Recv<T> {
    Msg(T),
    Deadline,
    Closed,
}

pub fn mailbox<T>(capacity: usize) -> (Outbox<T>, Inbox<T>) {
    assert!(capacity > 0);
    let shared = Arc::new(Shared {
        queue: Mutex::new(State {
            items: VecDeque::with_capacity(capacity),
            senders: 1,
            dropped: 0,
        }),
        ready: Condvar::new(),
        capacity,
    });
    (
        Outbox {
            shared: shared.clone(),
        },
        Inbox { shared },
    )
}

impl<T> Outbox<T> {
    /// Queues `msg`, dropping the oldest message if the mailbox is full. Never blocks.
    pub fn send(&self, msg: T) {
        let mut s = self.shared.queue.lock().unwrap_or_else(|p| p.into_inner());
        if s.items.len() == self.shared.capacity {
            s.items.pop_front();
            s.dropped += 1;
        }
        s.items.push_back(msg);
        drop(s);
        self.shared.ready.notify_one();
    }
}

impl<T> Clone for Outbox<T> {
    fn clone(&self) -> Self {
        self.shared
            .queue
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .senders += 1;
        Self {
            shared: self.shared.clone(),
        }
    }
}

impl<T> Drop for Outbox<T> {
    fn drop(&mut self) {
        let mut s = self.shared.queue.lock().unwrap_or_else(|p| p.into_inner());
        s.senders -= 1;
        if s.senders == 0 {
            drop(s);
            self.shared.ready.notify_all();
        }
    }
}

impl<T> Inbox<T> {
    pub(crate) fn recv_until(&self, deadline: Option<Instant>) -> Recv<T> {
        let mut s = self.shared.queue.lock().unwrap_or_else(|p| p.into_inner());
        loop {
            if let Some(m) = s.items.pop_front() {
                return Recv::Msg(m);
            }
            if s.senders == 0 {
                return Recv::Closed;
            }
            match deadline {
                None => s = self.shared.ready.wait(s).unwrap_or_else(|p| p.into_inner()),
                Some(d) => {
                    let now = Instant::now();
                    if d <= now {
                        return Recv::Deadline;
                    }
                    s = self
                        .shared
                        .ready
                        .wait_timeout(s, d - now)
                        .unwrap_or_else(|p| p.into_inner())
                        .0;
                }
            }
        }
    }

    /// Takes a waiting message without blocking.
    pub fn try_recv(&self) -> Option<T> {
        self.shared
            .queue
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .items
            .pop_front()
    }

    /// How many messages were dropped because the mailbox was full.
    pub fn dropped(&self) -> u64 {
        self.shared
            .queue
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .dropped
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn a_full_mailbox_drops_the_oldest_message() {
        let (tx, rx) = mailbox(2);
        tx.send(1);
        tx.send(2);
        tx.send(3);
        assert_eq!(rx.try_recv(), Some(2));
        assert_eq!(rx.try_recv(), Some(3));
        assert_eq!(rx.try_recv(), None);
        assert_eq!(rx.dropped(), 1);
    }

    #[test]
    fn receiving_waits_for_a_deadline_and_ends_when_senders_go() {
        let (tx, rx) = mailbox::<u8>(1);
        let start = Instant::now();
        assert!(matches!(
            rx.recv_until(Some(start + Duration::from_millis(15))),
            Recv::Deadline
        ));
        assert!(start.elapsed() >= Duration::from_millis(15));
        let tx2 = tx.clone();
        drop(tx);
        tx2.send(9);
        assert!(matches!(rx.recv_until(None), Recv::Msg(9)));
        drop(tx2);
        assert!(matches!(rx.recv_until(None), Recv::Closed));
    }
}
