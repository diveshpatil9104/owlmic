//! The camera stream between the carrier and the decoder: fragments in, whole frames out in
//! order. A decoder that falls behind clears the queue and waits for a keyframe, since H.264
//! frames can't be skipped one by one.

use super::reassembly::{EncodedFrame, Reassembler};
use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

/// About a quarter second at 30 fps.
const QUEUE: usize = 8;

pub struct VideoReceiver {
    reassembler: Mutex<Reassembler>,
    queue: Mutex<VecDeque<EncodedFrame>>,
    ready: Condvar,
    request_keyframe: Box<dyn Fn() + Send + Sync>,
}

impl VideoReceiver {
    /// `request_keyframe` asks the phone for one (through the App and Link Hubs).
    pub fn new(request_keyframe: impl Fn() + Send + Sync + 'static) -> Self {
        Self {
            reassembler: Mutex::new(Reassembler::default()),
            queue: Mutex::new(VecDeque::with_capacity(QUEUE)),
            ready: Condvar::new(),
            request_keyframe: Box::new(request_keyframe),
        }
    }

    /// One fragment, payload after the fragment header.
    #[allow(clippy::too_many_arguments)]
    pub fn fragment(
        &self,
        frame: u16,
        index: u8,
        count: u8,
        keyframe: bool,
        timestamp_us: u32,
        data: &[u8],
    ) {
        let pushed = self
            .reassembler
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .push(
                frame,
                index,
                count,
                keyframe,
                timestamp_us,
                data,
                Instant::now(),
            );
        if pushed.request_keyframe {
            (self.request_keyframe)();
        }
        let Some(f) = pushed.frame else { return };
        let mut q = self.queue.lock().unwrap_or_else(|p| p.into_inner());
        if q.len() == QUEUE && !f.keyframe {
            q.clear();
            drop(q);
            self.resync();
            return;
        }
        if f.keyframe {
            q.clear();
        }
        q.push_back(f);
        drop(q);
        self.ready.notify_one();
    }

    /// For the decoder thread: the next frame in order, or `None` after `wait`.
    pub fn next_frame(&self, wait: Duration) -> Option<EncodedFrame> {
        let q = self.queue.lock().unwrap_or_else(|p| p.into_inner());
        let (mut q, _) = self
            .ready
            .wait_timeout_while(q, wait, |q| q.is_empty())
            .unwrap_or_else(|p| p.into_inner());
        q.pop_front()
    }

    /// The decoder failed: drop what is queued and wait for a keyframe.
    pub fn resync(&self) {
        self.queue.lock().unwrap_or_else(|p| p.into_inner()).clear();
        let mut r = self.reassembler.lock().unwrap_or_else(|p| p.into_inner());
        r.lost_sync();
        if r.take_keyframe_request(Instant::now()) {
            (self.request_keyframe)();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn frames_come_out_in_order_and_a_slow_decoder_triggers_a_keyframe_request() {
        let asked = Arc::new(AtomicUsize::new(0));
        let a = asked.clone();
        let rx = VideoReceiver::new(move || {
            a.fetch_add(1, Ordering::SeqCst);
        });
        rx.fragment(1, 0, 1, true, 0, b"k");
        rx.fragment(2, 0, 1, false, 0, b"p");
        assert_eq!(
            rx.next_frame(Duration::ZERO).map(|f| f.data),
            Some(b"k".to_vec())
        );
        assert_eq!(
            rx.next_frame(Duration::ZERO).map(|f| f.data),
            Some(b"p".to_vec())
        );
        for f in 3..3 + QUEUE as u16 + 1 {
            rx.fragment(f, 0, 1, false, 0, b"p");
        }
        assert_eq!(asked.load(Ordering::SeqCst), 1);
        assert_eq!(rx.next_frame(Duration::ZERO), None, "the queue was dropped");
    }
}
