//! The PTY lane (T37, S33/S34): one phone-sized pseudo-terminal over the
//! pinned SSH connection — `request_pty` + `window_change` **without
//! reopening the channel**, and one bidirectional byte stream. No UI and no
//! UniFFI here; a later slice wires this to the terminal screen.

use std::{
    collections::VecDeque,
    sync::{Arc, Mutex as StdMutex},
    time::Duration,
};

use crate::profile::HostProfile;

/// The terminal type the host session runs in.
pub const TERMINAL_TYPE: &str = "xterm-256color";

/// A PTY-lane failure, typed for the UI: a refusal names the reason, and a
/// dead connection is a typed error — never a panic, never a silent hang.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PtyError {
    /// The host refused the channel, the PTY or the shell.
    Refused { detail: String },
    /// No answer within the caller's deadline.
    Timeout { detail: String },
    /// The SSH connection is gone.
    Disconnected,
    /// Anything else on the wire.
    Transport { detail: String },
}

impl std::fmt::Display for PtyError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PtyError::Refused { detail } => write!(formatter, "the host refused: {detail}"),
            PtyError::Timeout { detail } => write!(formatter, "timed out: {detail}"),
            PtyError::Disconnected => write!(formatter, "the connection is gone"),
            PtyError::Transport { detail } => write!(formatter, "{detail}"),
        }
    }
}

impl std::error::Error for PtyError {}

/// One message of the session channel, reduced to what a terminal needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PtyMsg {
    /// Bytes from the host (stdout merged with stderr).
    Data(Vec<u8>),
    /// The process's exit status, when the host sent one.
    ExitStatus(u32),
    /// The channel closed — locally or from the host.
    Close,
    /// A control message a terminal ignores (EOF, replies, adjustments).
    Ignored,
}

/// The minimal PTY-channel surface, so every stream rule is testable against
/// a fake with no network. Implemented for the real russh session channel
/// ([`ChannelPty`]) and never used as a trait object, so native `async fn`
/// is what we want here, not `async_trait` boxing.
#[allow(async_fn_in_trait)]
pub trait PtyLike {
    /// Request a pseudo-terminal of exactly `cols` × `rows`.
    async fn request_pty(
        &mut self,
        term: &str,
        cols: u32,
        rows: u32,
        echo: bool,
    ) -> Result<(), PtyError>;
    /// Ask the host to start the user's shell on it.
    async fn request_shell(&mut self) -> Result<(), PtyError>;
    /// Run `command` on the PTY instead of the default shell — a login
    /// shell started in a chosen working directory.
    async fn request_exec(&mut self, command: &str) -> Result<(), PtyError>;
    /// Reshape the terminal without reopening the channel.
    async fn window_change(&mut self, cols: u32, rows: u32) -> Result<(), PtyError>;
    /// The next channel message; a dead connection is a typed error.
    async fn next_msg(&mut self) -> Result<PtyMsg, PtyError>;
    /// Write bytes to the channel (keystrokes, paste).
    async fn write(&mut self, data: &[u8]) -> Result<(), PtyError>;
    /// Close the channel.
    async fn close(&mut self) -> Result<(), PtyError>;
}

/// Quote a value for a POSIX shell single-quoted word: wrap in single
/// quotes and escape an embedded single quote as `'\''`.
pub fn shell_quote(value: &str) -> String {
    let mut quoted = String::with_capacity(value.len() + 2);
    quoted.push('\'');
    for ch in value.chars() {
        if ch == '\'' {
            quoted.push_str("'\\''");
        } else {
            quoted.push(ch);
        }
    }
    quoted.push('\'');
    quoted
}

/// One terminal session over a [`PtyLike`] channel: the stream rules live
/// here, so the fake and the real channel obey the same contract.
pub struct PtySession<C: PtyLike> {
    channel: C,
    cols: u32,
    rows: u32,
    pending: VecDeque<u8>,
    ended: bool,
    status: Option<u32>,
}

/// The real PTY channel: one russh session channel.
pub struct ChannelPty {
    channel: russh::Channel<russh::client::Msg>,
}

/// A host-connected terminal: the pinned SSH session plus its PTY stream.
/// The transport rules (pins, keepalive, device key) are the shared lane's,
/// [`crate::requests::connect_pinned_ssh`].
pub struct HostPty {
    ssh: russh::client::Handle<crate::connection::KeyVerifier>,
    session: PtySession<ChannelPty>,
}

fn pty_transport_error(scope: &str, error: russh::Error) -> PtyError {
    match error {
        russh::Error::Disconnect
        | russh::Error::HUP
        | russh::Error::ConnectionTimeout
        | russh::Error::KeepaliveTimeout
        | russh::Error::InactivityTimeout
        | russh::Error::SendError
        | russh::Error::IO(_) => PtyError::Disconnected,
        _ => PtyError::Transport { detail: format!("{scope}: {error}") },
    }
}

impl<C: PtyLike> PtySession<C> {
    /// Open one terminal: a PTY of exactly the asked size (`echo` on) and
    /// the host's shell in it.
    pub async fn open(mut channel: C, cols: u32, rows: u32) -> Result<Self, PtyError> {
        channel.request_pty(TERMINAL_TYPE, cols, rows, true).await?;
        channel.request_shell().await?;
        Ok(Self {
            channel,
            cols,
            rows,
            pending: VecDeque::new(),
            ended: false,
            status: None,
        })
    }

    /// Open one terminal whose shell starts in `cwd`: a PTY of exactly the
    /// asked size (`echo` on) and the login shell executed with that working
    /// directory — `cd` first, then `exec` the shell, so the PTY never runs
    /// anything but the shell. The `exec` channel parses the command through
    /// the host's login shell (fish here), which does not understand POSIX
    /// `${…}` syntax, so the `cd` + `exec` live inside one `/bin/sh`
    /// invocation and the directory travels as a positional argument.
    pub async fn open_in(
        mut channel: C,
        cols: u32,
        rows: u32,
        cwd: &str,
    ) -> Result<Self, PtyError> {
        channel.request_pty(TERMINAL_TYPE, cols, rows, true).await?;
        const LAUNCH: &str = "cd -- \"$1\" && exec \"${SHELL:-/bin/bash}\" -l";
        let command = format!(
            "exec /bin/sh -c {} sh {}",
            shell_quote(LAUNCH),
            shell_quote(cwd)
        );
        channel.request_exec(&command).await?;
        Ok(Self {
            channel,
            cols,
            rows,
            pending: VecDeque::new(),
            ended: false,
            status: None,
        })
    }

    /// Reclaim the underlying channel (test fakes, teardown).
    pub fn into_channel(self) -> C {
        self.channel
    }

    /// The current terminal geometry.
    pub fn size(&self) -> (u32, u32) {
        (self.cols, self.rows)
    }

    /// The process's exit status, once the host sent one and the channel
    /// reached its end.
    pub fn exit_status(&self) -> Option<u32> {
        self.status
    }

    /// True once the channel reached its end of stream.
    pub fn is_ended(&self) -> bool {
        self.ended
    }

    /// Reshape the terminal in place: `window-change` on the same channel,
    /// no reopening, no loss of the byte stream.
    pub async fn resize(&mut self, cols: u32, rows: u32) -> Result<(), PtyError> {
        self.channel.window_change(cols, rows).await?;
        self.cols = cols;
        self.rows = rows;
        Ok(())
    }

    /// The next bytes from the host: whatever has arrived is delivered at
    /// once — a partial chunk reaches the caller, a chunk bigger than the
    /// buffer is delivered across reads, nothing is buffered silently.
    /// A closed channel is `Ok(0)`, end of stream, with the exit status
    /// carried in [`PtySession::exit_status`].
    pub async fn read(&mut self, buf: &mut [u8]) -> Result<usize, PtyError> {
        if self.ended {
            return Ok(0);
        }
        if !self.pending.is_empty() {
            let mut filled = 0usize;
            while filled < buf.len() {
                match self.pending.pop_front() {
                    Some(byte) => {
                        buf[filled] = byte;
                        filled += 1;
                    }
                    None => break,
                }
            }
            return Ok(filled);
        }
        if buf.is_empty() {
            return Ok(0);
        }
        loop {
            match self.channel.next_msg().await? {
                PtyMsg::Data(bytes) => {
                    let take = bytes.len().min(buf.len());
                    buf[..take].copy_from_slice(&bytes[..take]);
                    self.pending.extend(bytes[take..].iter().copied());
                    return Ok(take);
                }
                PtyMsg::ExitStatus(status) => self.status = Some(status),
                PtyMsg::Close => {
                    self.ended = true;
                    return Ok(0);
                }
                PtyMsg::Ignored => {}
            }
        }
    }

    /// [`PtySession::read`] with a deadline: a quiet stream answers with
    /// [`PtyError::Timeout`] and stays usable — the caller decides to wait
    /// again, close, or drop the session, and dropping never leaks the
    /// channel. Never a silent hang.
    pub async fn read_within(
        &mut self,
        buf: &mut [u8],
        within: Duration,
    ) -> Result<usize, PtyError> {
        match tokio::time::timeout(within, self.read(buf)).await {
            Ok(outcome) => outcome,
            Err(_) => Err(PtyError::Timeout {
                detail: "the terminal was quiet for too long".to_owned(),
            }),
        }
    }

    /// Write bytes to the host (keystrokes, paste) in one piece.
    pub async fn write(&mut self, data: &[u8]) -> Result<(), PtyError> {
        self.channel.write(data).await
    }

    /// Close the channel; later reads answer end-of-stream honestly.
    pub async fn close(&mut self) -> Result<(), PtyError> {
        self.ended = true;
        self.channel.close().await
    }
}

impl PtyLike for ChannelPty {
    async fn request_pty(
        &mut self,
        term: &str,
        cols: u32,
        rows: u32,
        echo: bool,
    ) -> Result<(), PtyError> {
        let mut modes: Vec<(russh::Pty, u32)> = Vec::new();
        if echo {
            modes.push((russh::Pty::ECHO, 1));
        }
        self.channel
            .request_pty(false, term, cols, rows, 0, 0, &modes)
            .await
            .map_err(|error| pty_transport_error("the PTY request failed", error))
    }

    async fn request_shell(&mut self) -> Result<(), PtyError> {
        self.channel
            .request_shell(false)
            .await
            .map_err(|error| pty_transport_error("the shell request failed", error))
    }

    async fn request_exec(&mut self, command: &str) -> Result<(), PtyError> {
        self.channel
            .exec(false, command)
            .await
            .map_err(|error| pty_transport_error("the exec request failed", error))
    }

    async fn window_change(&mut self, cols: u32, rows: u32) -> Result<(), PtyError> {
        self.channel
            .window_change(cols, rows, 0, 0)
            .await
            .map_err(|error| pty_transport_error("the window change failed", error))
    }

    async fn next_msg(&mut self) -> Result<PtyMsg, PtyError> {
        Ok(match self.channel.wait().await {
            Some(russh::ChannelMsg::Data { data }) => PtyMsg::Data(data.to_vec()),
            Some(russh::ChannelMsg::ExtendedData { data, .. }) => PtyMsg::Data(data.to_vec()),
            Some(russh::ChannelMsg::ExitStatus { exit_status }) => {
                PtyMsg::ExitStatus(exit_status)
            }
            Some(russh::ChannelMsg::Close) => PtyMsg::Close,
            // EOF, replies, window adjustments: control, not terminal bytes.
            Some(_) => PtyMsg::Ignored,
            // The channel's receiver ended: the connection is gone.
            None => PtyMsg::Close,
        })
    }

    async fn write(&mut self, data: &[u8]) -> Result<(), PtyError> {
        use tokio::io::AsyncWriteExt as _;
        let mut writer = self.channel.make_writer();
        writer
            .write_all(data)
            .await
            .map_err(|_| PtyError::Disconnected)?;
        writer
            .flush()
            .await
            .map_err(|_| PtyError::Disconnected)
    }

    async fn close(&mut self) -> Result<(), PtyError> {
        self.channel
            .close()
            .await
            .map_err(|error| pty_transport_error("the channel close failed", error))
    }
}

impl HostPty {
    async fn open_channel(
        profile: &HostProfile,
    ) -> Result<
        (
            russh::client::Handle<crate::connection::KeyVerifier>,
            russh::Channel<russh::client::Msg>,
        ),
        PtyError,
    > {
        let ssh =
            crate::requests::connect_pinned_ssh(profile, &std::sync::atomic::AtomicBool::new(false))
                .await
                .map_err(|error| PtyError::Transport { detail: error.to_string() })?;
        match ssh.channel_open_session().await {
            Ok(channel) => Ok((ssh, channel)),
            Err(error) => {
                let detail = format!("the session channel could not open: {error}");
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                Err(PtyError::Refused { detail })
            }
        }
    }

    /// One pinned SSH session carrying one PTY of the asked size, with the
    /// host's shell running in it. Host-key and transport failures come typed.
    pub async fn connect(profile: &HostProfile, cols: u32, rows: u32) -> Result<Self, PtyError> {
        let (ssh, channel) = Self::open_channel(profile).await?;
        match PtySession::open(ChannelPty { channel }, cols, rows).await {
            Ok(session) => Ok(Self { ssh, session }),
            Err(error) => {
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                Err(error)
            }
        }
    }

    /// [`HostPty::connect`], but the shell starts in `cwd` — a plain login
    /// shell, never an agent (T42).
    pub async fn connect_in(
        profile: &HostProfile,
        cols: u32,
        rows: u32,
        cwd: &str,
    ) -> Result<Self, PtyError> {
        let (ssh, channel) = Self::open_channel(profile).await?;
        match PtySession::open_in(ChannelPty { channel }, cols, rows, cwd).await {
            Ok(session) => Ok(Self { ssh, session }),
            Err(error) => {
                let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
                Err(error)
            }
        }
    }

    /// Split the pinned SSH session and its PTY stream (the pump owns the
    /// stream; the caller keeps the transport to disconnect at the end).
    pub fn into_parts(
        self,
    ) -> (
        russh::client::Handle<crate::connection::KeyVerifier>,
        PtySession<ChannelPty>,
    ) {
        (self.ssh, self.session)
    }

    /// The current terminal geometry.
    pub fn size(&self) -> (u32, u32) {
        self.session.size()
    }

    /// The process's exit status, once the channel reached its end.
    pub fn exit_status(&self) -> Option<u32> {
        self.session.exit_status()
    }

    /// True once the terminal stream reached its end.
    pub fn is_ended(&self) -> bool {
        self.session.is_ended()
    }

    /// Reshape the terminal in place, without reopening the channel.
    pub async fn resize(&mut self, cols: u32, rows: u32) -> Result<(), PtyError> {
        self.session.resize(cols, rows).await
    }

    /// The next bytes from the host, delivered the moment they arrive.
    pub async fn read(&mut self, buf: &mut [u8]) -> Result<usize, PtyError> {
        self.session.read(buf).await
    }

    /// [`HostPty::read`] with a deadline: quiet is [`PtyError::Timeout`],
    /// the stream stays usable, the channel never leaks.
    pub async fn read_within(
        &mut self,
        buf: &mut [u8],
        within: Duration,
    ) -> Result<usize, PtyError> {
        self.session.read_within(buf, within).await
    }

    /// Write bytes to the host (keystrokes, paste).
    pub async fn write(&mut self, data: &[u8]) -> Result<(), PtyError> {
        self.session.write(data).await
    }

    /// Close the terminal channel; the SSH session stays for the caller.
    pub async fn close(&mut self) -> Result<(), PtyError> {
        self.session.close().await
    }

    /// Close the terminal and the whole SSH session.
    pub async fn disconnect(mut self) {
        let _ = self.session.close().await;
        let _ = self
            .ssh
            .disconnect(russh::Disconnect::ByApplication, "", "en")
            .await;
    }
}

/// One command the UI sends into a running terminal pump.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PtyCommand {
    /// Keystrokes / paste, delivered verbatim in one piece.
    Write(Vec<u8>),
    /// Reshape the terminal without reopening the channel.
    Resize(u32, u32),
    /// Orderly shutdown: close the channel and end the pump.
    Close,
}

/// The state a terminal pump shares with the UI: bytes as they arrive
/// (never batched behind a full buffer), the end of stream, the host's
/// exit status, the current geometry, and the reason it ended.
#[derive(Default)]
pub struct PtyShared {
    pub output: VecDeque<u8>,
    pub ended: bool,
    pub exit_status: Option<u32>,
    pub size: (u32, u32),
    pub last_error: Option<String>,
}

impl PtyShared {
    /// Drain up to `max` arrived bytes; an empty answer means none are
    /// pending, never an error.
    pub fn drain(&mut self, max: usize) -> Vec<u8> {
        let take = max.min(self.output.len());
        self.output.drain(..take).collect()
    }
}

/// The terminal pump: read the PTY and deliver bytes as they arrive while
/// serving write/resize/close commands on the same loop — a quiet shell
/// (no output) still answers a write or a resize the moment it is asked.
/// Returns the session so a test can reclaim its fake channel; the real
/// caller drops it with the SSH session.
pub async fn pump_terminal<C: PtyLike>(
    mut session: PtySession<C>,
    mut commands: tokio::sync::mpsc::Receiver<PtyCommand>,
    shared: Arc<StdMutex<PtyShared>>,
) -> PtySession<C> {
    let mut buf = [0u8; 4096];
    loop {
        tokio::select! {
            read = session.read(&mut buf) => {
                match read {
                    Ok(0) => {
                        let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        state.ended = true;
                        state.exit_status = session.exit_status();
                        break;
                    }
                    Ok(n) => {
                        let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        state.output.extend(&buf[..n]);
                    }
                    Err(error) => {
                        let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        state.ended = true;
                        state.last_error = Some(error.to_string());
                        break;
                    }
                }
            }
            command = commands.recv() => {
                match command {
                    Some(PtyCommand::Write(bytes)) => {
                        if session.write(&bytes).await.is_err() {
                            let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                            state.ended = true;
                            state.last_error = Some("the terminal write failed".to_owned());
                            break;
                        }
                    }
                    Some(PtyCommand::Resize(cols, rows)) => {
                        if session.resize(cols, rows).await.is_err() {
                            let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                            state.ended = true;
                            state.last_error = Some("the terminal resize failed".to_owned());
                            break;
                        }
                        let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        state.size = (cols, rows);
                    }
                    Some(PtyCommand::Close) | None => {
                        let _ = session.close().await;
                        let mut state = shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                        state.ended = true;
                        break;
                    }
                }
            }
        }
    }
    session
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    enum TestStep {
        Data(Vec<u8>),
        Exit(u32),
        Close,
        #[allow(dead_code)]
        Ignored,
        Sleep(Duration),
        Fail(PtyError),
    }

    /// The fake terminal: a scripted message stream plus the record of
    /// everything the session asked for. No network anywhere.
    struct FakePty {
        script: Mutex<VecDeque<TestStep>>,
        pty_requests: Mutex<Vec<(String, u32, u32, bool)>>,
        shell_requests: Mutex<usize>,
        exec_requests: Mutex<Vec<String>>,
        window_changes: Mutex<Vec<(u32, u32)>>,
        writes: Mutex<Vec<u8>>,
        close_requests: Mutex<usize>,
        always_fail: Option<PtyError>,
    }

    impl FakePty {
        fn new(script: Vec<TestStep>) -> Self {
            Self {
                script: Mutex::new(script.into()),
                pty_requests: Mutex::new(Vec::new()),
                shell_requests: Mutex::new(0),
                exec_requests: Mutex::new(Vec::new()),
                window_changes: Mutex::new(Vec::new()),
                writes: Mutex::new(Vec::new()),
                close_requests: Mutex::new(0),
                always_fail: None,
            }
        }

        fn that_always_fails(mut self, error: PtyError) -> Self {
            self.always_fail = Some(error);
            self
        }

        fn pty_request_records(&self) -> Vec<(String, u32, u32, bool)> {
            self.pty_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
        }

        fn shell_request_count(&self) -> usize {
            *self
                .shell_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
        }

        fn exec_request_records(&self) -> Vec<String> {
            self.exec_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
        }

        fn window_change_records(&self) -> Vec<(u32, u32)> {
            self.window_changes
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
        }

        fn written_bytes(&self) -> Vec<u8> {
            self.writes
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
        }

        fn close_request_count(&self) -> usize {
            *self
                .close_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
        }
    }

    impl PtyLike for FakePty {
        async fn request_pty(
            &mut self,
            term: &str,
            cols: u32,
            rows: u32,
            echo: bool,
        ) -> Result<(), PtyError> {
            self.pty_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .push((term.to_owned(), cols, rows, echo));
            Ok(())
        }

        async fn request_shell(&mut self) -> Result<(), PtyError> {
            *self
                .shell_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) += 1;
            Ok(())
        }

        async fn request_exec(&mut self, command: &str) -> Result<(), PtyError> {
            self.exec_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .push(command.to_owned());
            Ok(())
        }

        async fn window_change(&mut self, cols: u32, rows: u32) -> Result<(), PtyError> {
            self.window_changes
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .push((cols, rows));
            Ok(())
        }

        async fn next_msg(&mut self) -> Result<PtyMsg, PtyError> {
            if let Some(error) = &self.always_fail {
                return Err(error.clone());
            }
            loop {
                let step = self
                    .script
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .pop_front();
                match step {
                    None => return Ok(PtyMsg::Close),
                    Some(TestStep::Data(bytes)) => return Ok(PtyMsg::Data(bytes)),
                    Some(TestStep::Exit(status)) => return Ok(PtyMsg::ExitStatus(status)),
                    Some(TestStep::Close) => return Ok(PtyMsg::Close),
                    Some(TestStep::Ignored) => return Ok(PtyMsg::Ignored),
                    Some(TestStep::Fail(error)) => return Err(error),
                    Some(TestStep::Sleep(how_long)) => tokio::time::sleep(how_long).await,
                }
            }
        }

        async fn write(&mut self, data: &[u8]) -> Result<(), PtyError> {
            if let Some(error) = &self.always_fail {
                return Err(error.clone());
            }
            self.writes
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .extend_from_slice(data);
            Ok(())
        }

        async fn close(&mut self) -> Result<(), PtyError> {
            *self
                .close_requests
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) += 1;
            Ok(())
        }
    }

    /// Reclaim the fake from a session for its record checks.
    fn fake_of<C: PtyLike>(session: PtySession<C>) -> C {
        session.into_channel()
    }

    // --- T37 rule 1: the PTY is requested with exactly what was given ----

    #[tokio::test]
    async fn open_pty_requests_exactly_rows_cols_with_the_terminal_type_and_echo() {
        let fake = FakePty::new(Vec::new());
        let session = PtySession::open(fake, 80, 24).await.unwrap_or_else(|error| {
            panic!("open failed: {error}")
        });
        let fake = fake_of(session);
        assert_eq!(
            fake.pty_request_records(),
            vec![(TERMINAL_TYPE.to_owned(), 80, 24, true)],
            "one pty request, exactly the asked size, echo on"
        );
        assert_eq!(fake.shell_request_count(), 1, "open starts the shell");
    }

    // --- T37 rule 3: resize reshapes without reopening -------------------

    #[tokio::test]
    async fn a_resize_sends_window_change_with_the_new_size_and_never_reopens() {
        let fake = FakePty::new(Vec::new());
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        session
            .resize(38, 96)
            .await
            .unwrap_or_else(|error| panic!("resize failed: {error}"));
        let fake = session.into_channel();
        assert_eq!(fake.window_change_records(), vec![(38, 96)]);
        assert_eq!(
            fake.pty_request_records(),
            vec![(TERMINAL_TYPE.to_owned(), 80, 24, true)],
            "the channel was never reopened: still only the open-time request"
        );
        assert_eq!(fake.shell_request_count(), 1);
    }

    // --- T37 rule 2: reads reach the caller complete and in order --------

    #[tokio::test]
    async fn a_partial_chunk_reaches_the_caller_at_once_in_order() {
        let fake = FakePty::new(vec![
            TestStep::Data(b"hel".to_vec()),
            TestStep::Data(b"lo world".to_vec()),
            TestStep::Close,
        ]);
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let mut buf = [0u8; 64];
        let first = session
            .read(&mut buf)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(&buf[..first], b"hel", "a short chunk is delivered the moment it arrives");
        let mut buf2 = [0u8; 64];
        let second = session
            .read(&mut buf2)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(&buf2[..second], b"lo world", "chunks arrive in order, nothing re-ordered");
    }

    #[tokio::test]
    async fn a_chunk_bigger_than_the_buffer_is_never_lost_to_silent_buffering() {
        let fake = FakePty::new(vec![TestStep::Data(b"abcdefgh".to_vec()), TestStep::Close]);
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let mut small = [0u8; 4];
        let first = session
            .read(&mut small)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(&small[..first], b"abcd");
        let second = session
            .read(&mut small)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(&small[..second], b"efgh", "the remainder is delivered on the next read");
    }

    #[tokio::test]
    async fn keystrokes_reach_the_host_verbatim_and_in_one_piece() {
        let fake = FakePty::new(Vec::new());
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        session
            .write(b"systemctl status herdr\n")
            .await
            .unwrap_or_else(|error| panic!("write failed: {error}"));
        let fake = session.into_channel();
        assert_eq!(fake.written_bytes(), b"systemctl status herdr\n".to_vec());
    }

    // --- T37 rule 4: a closed channel ends the stream honestly -----------

    #[tokio::test]
    async fn a_closed_channel_reports_end_of_stream_with_the_exit_status() {
        let fake = FakePty::new(vec![TestStep::Exit(7), TestStep::Close]);
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let mut buf = [0u8; 16];
        let first = session
            .read(&mut buf)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(first, 0, "a closed channel is end-of-stream, never an error");
        assert!(session.is_ended());
        assert_eq!(session.exit_status(), Some(7), "the host's exit status travels");
        let second = session
            .read(&mut buf)
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(second, 0, "reads after the end still answer honestly, never hang");
    }

    // --- T37 rule 5: a broken connection is the typed error --------------

    #[tokio::test]
    async fn a_broken_connection_is_the_typed_error_never_a_panic() {
        let broken = PtyError::Disconnected;
        let fake = FakePty::new(vec![TestStep::Fail(broken.clone())]).that_always_fails(broken.clone());
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let read = session.read(&mut [0u8; 4]).await;
        assert!(matches!(read, Err(PtyError::Disconnected)), "read: {read:?}");
        let written = session.write(b"hi").await;
        assert!(matches!(written, Err(PtyError::Disconnected)), "write: {written:?}");
    }

    // --- T37 rule 6: a quiet stream can be timed out and released --------

    #[tokio::test]
    async fn a_quiet_stream_times_out_as_a_typed_error_without_losing_the_channel() {
        let fake = FakePty::new(vec![
            TestStep::Sleep(Duration::from_millis(300)),
            TestStep::Data(b"later".to_vec()),
        ]);
        let mut session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let mut buf = [0u8; 64];
        let quiet = session.read_within(&mut buf, Duration::from_millis(30)).await;
        assert!(
            matches!(quiet, Err(PtyError::Timeout { .. })),
            "a quiet stream is the typed timeout, never a hang: {quiet:?}"
        );
        let after = session
            .read_within(&mut buf, Duration::from_secs(2))
            .await
            .unwrap_or_else(|error| panic!("read failed: {error}"));
        assert_eq!(&buf[..after], b"later", "the channel survives a timed-out read");
        session.close().await.unwrap_or_else(|error| panic!("close failed: {error}"));
        let fake = session.into_channel();
        assert_eq!(fake.close_request_count(), 1, "an explicit close releases the channel");
    }

    // --- T42: the login shell starts in the asked working directory -----

    #[test]
    fn shell_quote_wraps_and_escapes_embedded_quotes() {
        assert_eq!(shell_quote("/srv/a"), "'/srv/a'");
        assert_eq!(shell_quote("/srv/o'reilly"), "'/srv/o'\\''reilly'");
        assert_eq!(shell_quote(""), "''");
    }

    #[tokio::test]
    async fn open_in_requests_a_pty_and_execs_a_login_shell_in_the_cwd() {
        let fake = FakePty::new(Vec::new());
        let session = PtySession::open_in(fake, 80, 24, "/srv/a")
            .await
            .unwrap_or_else(|error| panic!("open_in failed: {error}"));
        let fake = fake_of(session);
        assert_eq!(
            fake.pty_request_records(),
            vec![(TERMINAL_TYPE.to_owned(), 80, 24, true)],
            "the PTY is requested at the asked size"
        );
        assert_eq!(
            fake.exec_request_records(),
            vec!["exec /bin/sh -c 'cd -- \"$1\" && exec \"${SHELL:-/bin/bash}\" -l' sh '/srv/a'".to_owned()],
            "the exec is one POSIX sh invocation so the host's login shell never parses our syntax"
        );
        assert_eq!(fake.shell_request_count(), 0, "no default-home shell is started first");
    }

    #[tokio::test]
    async fn open_in_escapes_a_cwd_with_a_single_quote() {
        let fake = FakePty::new(Vec::new());
        let session = PtySession::open_in(fake, 80, 24, "/srv/o'reilly")
            .await
            .unwrap_or_else(|error| panic!("open_in failed: {error}"));
        let fake = fake_of(session);
        assert_eq!(
            fake.exec_request_records(),
            vec!["exec /bin/sh -c 'cd -- \"$1\" && exec \"${SHELL:-/bin/bash}\" -l' sh '/srv/o'\\''reilly'".to_owned()]
        );
    }

    // --- T42: the pump maps UI requests to the byte stream ---------------

    #[tokio::test]
    async fn the_pump_delivers_output_as_it_arrives_and_serves_write_resize_close() {
        let fake = FakePty::new(vec![
            TestStep::Sleep(Duration::from_millis(40)),
            TestStep::Data(b"hello".to_vec()),
            // Stay alive after the burst so Close still has a listener.
            TestStep::Sleep(Duration::from_secs(5)),
        ]);
        let session = PtySession::open(fake, 80, 24)
            .await
            .unwrap_or_else(|error| panic!("open failed: {error}"));
        let shared = Arc::new(StdMutex::new(PtyShared::default()));
        let (tx, rx) = tokio::sync::mpsc::channel(8);
        let pump_shared = shared.clone();
        let pump = tokio::spawn(async move { pump_terminal(session, rx, pump_shared).await });

        tx.send(PtyCommand::Write(b"ls\n".to_vec()))
            .await
            .unwrap_or_else(|error| panic!("write send failed: {error}"));
        tx.send(PtyCommand::Resize(38, 20))
            .await
            .unwrap_or_else(|error| panic!("resize send failed: {error}"));

        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            if !shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner).output.is_empty() {
                break;
            }
            assert!(std::time::Instant::now() < deadline, "output never arrived");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        tx.send(PtyCommand::Close)
            .await
            .unwrap_or_else(|error| panic!("close send failed: {error}"));

        let session = pump
            .await
            .unwrap_or_else(|error| panic!("pump failed: {error}"));
        let fake = session.into_channel();
        assert_eq!(fake.written_bytes(), b"ls\n".to_vec(), "write maps to the channel verbatim");
        assert_eq!(fake.window_change_records(), vec![(38, 20)], "resize maps to window-change");
        assert_eq!(
            shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner).drain(64),
            b"hello".to_vec(),
            "the host's bytes reach the shared buffer as they arrive"
        );
        assert!(shared.lock().unwrap_or_else(std::sync::PoisonError::into_inner).ended);
        assert_eq!(fake.close_request_count(), 1, "close releases the channel");
    }

    // --- The real live machine (never a CI expectation) ------------------

    /// Prove the size travels on the real host: open a PTY of the asked
    /// size to sshd, run `stty size` inside it, and check the answer.
    #[test]
    #[ignore = "live probe: needs a reachable sshd and a registered device key; opens one real PTY and runs stty size"]
    fn t37_pty_live_stty_size() {
        use std::{env as process_env, path::PathBuf, time::Instant};
        let profile = HostProfile {
            host: process_env::var("GENTLE_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned()),
            port: process_env::var("GENTLE_SSH_PORT")
                .ok()
                .and_then(|value| value.parse().ok())
                .unwrap_or(22),
            user: process_env::var("GENTLE_SSH_USER").unwrap_or_else(|_| "erickfm".to_owned()),
            identity_path: PathBuf::from(
                process_env::var("GENTLE_SSH_KEY").unwrap_or_else(|_| {
                    "/mnt/WD-Black/test_tmp/gentle-companion/t26-probe-key".to_owned()
                }),
            ),
            known_hosts_path: PathBuf::from(
                process_env::var("GENTLE_KNOWN_HOSTS").unwrap_or_else(|_| {
                    "/mnt/WD-Black/test_tmp/gentle-companion/t8-known-hosts".to_owned()
                }),
            ),
        };
        let ask_cols: u32 = process_env::var("GENTLE_COLS")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(97);
        let ask_rows: u32 = process_env::var("GENTLE_ROWS")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(31);
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap_or_else(|error| panic!("no tokio runtime: {error}"));
        runtime.block_on(async move {
            let mut host =
                HostPty::connect(&profile, ask_cols, ask_rows).await.unwrap_or_else(|error| {
                    panic!("connect failed: {error}")
                });
            host.write(b"stty size\n").await.unwrap_or_else(|error| panic!("write failed: {error}"));
            let mut collected: Vec<u8> = Vec::new();
            let deadline = Instant::now() + Duration::from_secs(20);
            let answer = loop {
                if Instant::now() >= deadline {
                    break None;
                }
                let mut buf = [0u8; 512];
                match host.read_within(&mut buf, Duration::from_secs(3)).await {
                    Ok(0) => break None,
                    Ok(n) => {
                        collected.extend_from_slice(&buf[..n]);
                        let text = String::from_utf8_lossy(&collected);
                        let parsed = text.lines().find_map(|line| {
                            let tokens: Vec<&str> = line
                                .split_whitespace()
                                .filter(|token| token.chars().all(|c| c.is_ascii_digit()))
                                .collect();
                            match tokens.as_slice() {
                                [rows, cols] => Some((rows.parse::<u32>().ok()?, cols.parse::<u32>().ok()?)),
                                _ => None,
                            }
                        });
                        if parsed.is_some() {
                            break parsed;
                        }
                    }
                    Err(PtyError::Timeout { .. }) => {}
                    Err(other) => panic!("the terminal stream broke: {other}"),
                }
            };
            let (rows, cols) = answer
                .unwrap_or_else(|| panic!("stty size never answered: {:?}", String::from_utf8_lossy(&collected)))
                ;
            println!("stty size: {rows} rows x {cols} cols (asked {ask_rows} x {ask_cols})");
            assert_eq!((rows, cols), (ask_rows, ask_cols), "the photo-sized grid must be exactly the asked one");
            host.disconnect().await;
        });
    }
}
