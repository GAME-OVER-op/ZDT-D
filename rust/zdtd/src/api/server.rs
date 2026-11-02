use anyhow::Result;
use std::{io::Write, net::TcpListener, sync::{Arc, atomic::{AtomicUsize, Ordering}}, thread, time::Duration};

use crate::runtime::daemon::SharedState;
use super::handle_connection;

pub fn serve(state: SharedState, bind: &str) -> Result<()> {
    /// Max number of concurrent connection handler threads.
    ///
    /// The API is local-only (127.0.0.1) but we still cap concurrency to avoid
    /// accidental flooding (or a buggy client) spawning unbounded threads.
    const MAX_INFLIGHT: usize = 64;

    let listener = TcpListener::bind(bind)?;
    let inflight = Arc::new(AtomicUsize::new(0));

    for conn in listener.incoming() {
        match conn {
            Ok(mut stream) => {
                // Apply small write timeout so we don't block forever on slow clients.
                let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
                let _ = stream.set_nodelay(true);

                // Любое обращение к API — признак того, что демоном пользуются:
                // сбрасываем таймер простоя и мгновенно поднимаем спящие наблюдатели,
                // чтобы ответ ушёл уже по свежим данным.
                crate::runtime::idle::touch();

                // Concurrency gate
                let now = inflight.fetch_add(1, Ordering::AcqRel) + 1;
                if now > MAX_INFLIGHT {
                    inflight.fetch_sub(1, Ordering::AcqRel);
                    let _ = stream.write_all(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    continue;
                }

                let st = state.clone();
                let inflight2 = inflight.clone();

                thread::spawn(move || {
                    // Ensure we always decrement inflight even if handler errors.
                    struct ConnGuard(Arc<AtomicUsize>);
                    impl Drop for ConnGuard {
                        fn drop(&mut self) {
                            self.0.fetch_sub(1, Ordering::AcqRel);
                        }
                    }
                    let _guard = ConnGuard(inflight2);

                    let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        handle_connection(stream, st)
                    }));
                    match res {
                        Ok(Ok(())) => {}
                        Ok(Err(e)) => log::warn!("api request failed: {e:#}"),
                        Err(_) => log::error!("api request handler panicked"),
                    }
                });
            }
            Err(_) => continue,
        }
    }

    Ok(())
}
