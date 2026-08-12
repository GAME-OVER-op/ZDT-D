use std::{
    future::pending,
    io,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpStream,
    sync::Notify,
    time::Instant,
};

const RELAY_BUFFER_SIZE: usize = 16 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RelayDirection {
    ClientToRemote,
    RemoteToClient,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RelayEndpoint {
    Client,
    Remote,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RelayOperation {
    Read,
    Write,
}

#[derive(Debug)]
pub enum RelayTermination {
    Clean,
    IoError {
        endpoint: RelayEndpoint,
        operation: RelayOperation,
        error: io::Error,
    },
    FirstResponseTimeout,
    HalfCloseTimeout {
        first_closed: RelayDirection,
    },
}

#[derive(Debug)]
pub struct RelayReport {
    pub client_to_remote: u64,
    pub remote_to_client: u64,
    pub client_eof: bool,
    pub remote_eof: bool,
    pub termination: RelayTermination,
}

#[derive(Default)]
struct RelayProgress {
    client_to_remote: AtomicU64,
    remote_to_client: AtomicU64,
    changed: Notify,
}

impl RelayProgress {
    fn add(&self, direction: RelayDirection, bytes: usize) {
        let counter = match direction {
            RelayDirection::ClientToRemote => &self.client_to_remote,
            RelayDirection::RemoteToClient => &self.remote_to_client,
        };
        counter.fetch_add(bytes as u64, Ordering::Relaxed);
        self.changed.notify_one();
    }

    fn snapshot(&self) -> (u64, u64) {
        (
            self.client_to_remote.load(Ordering::Relaxed),
            self.remote_to_client.load(Ordering::Relaxed),
        )
    }
}

#[derive(Debug)]
enum DirectionEnd {
    Eof,
    IoError {
        endpoint: RelayEndpoint,
        operation: RelayOperation,
        error: io::Error,
    },
}

/// Relay an established DNSCrypt/DoH TCP tunnel without imposing a generic
/// idle timeout. Two bounded failure cases are supervised instead:
///
/// * once the client has sent the first payload bytes, the remote side must
///   prove the data-plane before DNSCrypt's own query timeout expires;
/// * when the client write half reaches EOF, the remote read half gets a
///   bounded drain window for a final response; remote EOF closes immediately.
///
/// The copies run as child futures of the caller rather than detached Tokio
/// tasks. Cancelling the client handler therefore drops both socket halves and
/// cannot leak relay tasks during shutdown or task abortion.
pub async fn relay_bidirectional(
    client: TcpStream,
    remote: TcpStream,
    first_response_timeout: Duration,
    half_close_timeout: Duration,
) -> RelayReport {
    let progress = Arc::new(RelayProgress::default());
    let (mut client_read, mut client_write) = client.into_split();
    let (mut remote_read, mut remote_write) = remote.into_split();

    let mut client_to_remote = Box::pin(copy_direction(
        &mut client_read,
        &mut remote_write,
        RelayDirection::ClientToRemote,
        RelayEndpoint::Client,
        RelayEndpoint::Remote,
        progress.clone(),
    ));
    let mut remote_to_client = Box::pin(copy_direction(
        &mut remote_read,
        &mut client_write,
        RelayDirection::RemoteToClient,
        RelayEndpoint::Remote,
        RelayEndpoint::Client,
        progress.clone(),
    ));
    let mut missing_first_response = Box::pin(wait_for_missing_first_response(
        progress.clone(),
        first_response_timeout,
    ));

    enum FirstEvent {
        ClientToRemote(DirectionEnd),
        RemoteToClient(DirectionEnd),
        MissingFirstResponse,
    }

    // Keep the select itself separate from the follow-up drain. This releases
    // the select macro's mutable borrows before we await the opposite copy
    // future, which also keeps the ownership/cancellation behavior explicit.
    let first_event = tokio::select! {
        first = client_to_remote.as_mut() => FirstEvent::ClientToRemote(first),
        first = remote_to_client.as_mut() => FirstEvent::RemoteToClient(first),
        _ = missing_first_response.as_mut() => FirstEvent::MissingFirstResponse,
    };

    match first_event {
        FirstEvent::ClientToRemote(first) => {
            finish_after_direction(
                first,
                RelayDirection::ClientToRemote,
                remote_to_client.as_mut(),
                half_close_timeout,
                &progress,
            )
            .await
        }
        FirstEvent::RemoteToClient(first) => {
            finish_after_direction(
                first,
                RelayDirection::RemoteToClient,
                client_to_remote.as_mut(),
                half_close_timeout,
                &progress,
            )
            .await
        }
        FirstEvent::MissingFirstResponse => {
            let (client_to_remote, remote_to_client) = progress.snapshot();
            RelayReport {
                client_to_remote,
                remote_to_client,
                client_eof: false,
                remote_eof: false,
                termination: RelayTermination::FirstResponseTimeout,
            }
        }
    }
}

async fn finish_after_direction<F>(
    first: DirectionEnd,
    first_direction: RelayDirection,
    second: std::pin::Pin<&mut F>,
    half_close_timeout: Duration,
    progress: &RelayProgress,
) -> RelayReport
where
    F: std::future::Future<Output = DirectionEnd>,
{
    let mut client_eof = false;
    let mut remote_eof = false;

    match first {
        DirectionEnd::IoError { endpoint, operation, error } => {
            let (client_to_remote, remote_to_client) = progress.snapshot();
            return RelayReport {
                client_to_remote,
                remote_to_client,
                client_eof,
                remote_eof,
                termination: RelayTermination::IoError { endpoint, operation, error },
            };
        }
        DirectionEnd::Eof => match first_direction {
            RelayDirection::ClientToRemote => client_eof = true,
            RelayDirection::RemoteToClient => {
                // D2S only carries request/response DNS transports. Once the
                // remote side has reached EOF there can be no further DNS/DoH
                // response to drain, so keeping the client->remote half alive is
                // only a resource leak. Close the whole relay immediately.
                remote_eof = true;
                let (client_to_remote, remote_to_client) = progress.snapshot();
                return RelayReport {
                    client_to_remote,
                    remote_to_client,
                    client_eof,
                    remote_eof,
                    termination: RelayTermination::Clean,
                };
            }
        },
    }

    match tokio::time::timeout(half_close_timeout, second).await {
        Ok(DirectionEnd::Eof) => {
            match first_direction {
                RelayDirection::ClientToRemote => remote_eof = true,
                RelayDirection::RemoteToClient => client_eof = true,
            }
            let (client_to_remote, remote_to_client) = progress.snapshot();
            RelayReport {
                client_to_remote,
                remote_to_client,
                client_eof,
                remote_eof,
                termination: RelayTermination::Clean,
            }
        }
        Ok(DirectionEnd::IoError { endpoint, operation, error }) => {
            let (client_to_remote, remote_to_client) = progress.snapshot();
            RelayReport {
                client_to_remote,
                remote_to_client,
                client_eof,
                remote_eof,
                termination: RelayTermination::IoError { endpoint, operation, error },
            }
        }
        Err(_) => {
            let (client_to_remote, remote_to_client) = progress.snapshot();
            RelayReport {
                client_to_remote,
                remote_to_client,
                client_eof,
                remote_eof,
                termination: RelayTermination::HalfCloseTimeout { first_closed: first_direction },
            }
        }
    }
}

async fn copy_direction<R, W>(
    reader: &mut R,
    writer: &mut W,
    direction: RelayDirection,
    read_endpoint: RelayEndpoint,
    write_endpoint: RelayEndpoint,
    progress: Arc<RelayProgress>,
) -> DirectionEnd
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buffer = [0u8; RELAY_BUFFER_SIZE];
    loop {
        let read = match reader.read(&mut buffer).await {
            Ok(0) => {
                // Propagate the TCP half-close, but do not turn a shutdown race
                // into a data-plane failure. The supervisor will drop both halves
                // if the opposite direction does not finish in time.
                let _ = writer.shutdown().await;
                return DirectionEnd::Eof;
            }
            Ok(read) => read,
            Err(error) => {
                return DirectionEnd::IoError {
                    endpoint: read_endpoint,
                    operation: RelayOperation::Read,
                    error,
                };
            }
        };

        if let Err(error) = writer.write_all(&buffer[..read]).await {
            return DirectionEnd::IoError {
                endpoint: write_endpoint,
                operation: RelayOperation::Write,
                error,
            };
        }
        progress.add(direction, read);
    }
}

async fn wait_for_missing_first_response(progress: Arc<RelayProgress>, timeout: Duration) {
    // Do not start a timer merely because an HTTP/1.1 or HTTP/2 keep-alive
    // connection is idle. Arm it only after actual client payload is forwarded.
    loop {
        let changed = progress.changed.notified();
        let (client_to_remote, remote_to_client) = progress.snapshot();
        if remote_to_client > 0 {
            pending::<()>().await;
        }
        if client_to_remote > 0 {
            break;
        }
        changed.await;
    }

    let deadline = Instant::now() + timeout;
    loop {
        let changed = progress.changed.notified();
        if progress.remote_to_client.load(Ordering::Relaxed) > 0 {
            // The route has proved its data-plane. From here on there is no
            // generic inactivity timeout; normal long-lived DoH connections are
            // allowed to remain idle until one side actually closes.
            pending::<()>().await;
        }

        tokio::select! {
            _ = tokio::time::sleep_until(deadline) => {
                if progress.remote_to_client.load(Ordering::Relaxed) == 0 {
                    return;
                }
                pending::<()>().await;
            }
            _ = changed => {}
        }
    }
}
