use crate::common::config::deserialize_boolish;
use serde::{Deserialize, Serialize};

fn serialize_bool_as_bool<S>(value: &bool, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.serialize_bool(*value)
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnabledJson {
    #[serde(default, deserialize_with = "deserialize_boolish", serialize_with = "serialize_bool_as_bool")]
    pub enabled: bool,
}

impl Default for EnabledJson {
    fn default() -> Self {
        Self { enabled: false }
    }
}

impl EnabledJson {
    pub fn normalized(&self) -> Self {
        Self {
            enabled: self.enabled,
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
}
#[derive(Debug, Clone, Copy)]
pub(super) enum Backend {
    Iptables,
    Ip6tables,
}

impl Backend {
    pub(super) fn cmd(self) -> &'static str {
        match self {
            Self::Iptables => "iptables",
            Self::Ip6tables => "ip6tables",
        }
    }

    pub(super) fn label(self) -> &'static str {
        match self {
            Self::Iptables => "IPv4",
            Self::Ip6tables => "IPv6",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum TransportProtocol {
    Tcp,
    Udp,
}

impl TransportProtocol {
    pub(super) fn name(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) enum LocalMatchMode {
    LoAndDestLoopback,
    DestLoopback,
    LoopbackIface,
    DstTypeLocal,
}

impl LocalMatchMode {
    pub(super) fn describe(self, backend: Backend) -> &'static str {
        match (self, backend) {
            (Self::LoAndDestLoopback, Backend::Iptables) => "-o lo -d 127.0.0.1",
            (Self::DestLoopback, Backend::Iptables) => "-d 127.0.0.1",
            (Self::LoopbackIface, Backend::Iptables) => "-o lo",
            (Self::DstTypeLocal, Backend::Iptables) => "-m addrtype --dst-type LOCAL",
            (Self::LoAndDestLoopback, Backend::Ip6tables) => "-o lo -d ::1",
            (Self::DestLoopback, Backend::Ip6tables) => "-d ::1",
            (Self::LoopbackIface, Backend::Ip6tables) => "-o lo",
            (Self::DstTypeLocal, Backend::Ip6tables) => "-m addrtype --dst-type LOCAL",
        }
    }

    pub(super) fn push_args(self, backend: Backend, args: &mut Vec<String>) {
        match (self, backend) {
            (Self::LoAndDestLoopback, Backend::Iptables) => {
                args.push("-o".into());
                args.push("lo".into());
                args.push("-d".into());
                args.push("127.0.0.1".into());
            }
            (Self::DestLoopback, Backend::Iptables) => {
                args.push("-d".into());
                args.push("127.0.0.1".into());
            }
            (Self::LoopbackIface, _) => {
                args.push("-o".into());
                args.push("lo".into());
            }
            (Self::DstTypeLocal, _) => {
                args.push("-m".into());
                args.push("addrtype".into());
                args.push("--dst-type".into());
                args.push("LOCAL".into());
            }
            (Self::LoAndDestLoopback, Backend::Ip6tables) => {
                args.push("-o".into());
                args.push("lo".into());
                args.push("-d".into());
                args.push("::1".into());
            }
            (Self::DestLoopback, Backend::Ip6tables) => {
                args.push("-d".into());
                args.push("::1".into());
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) enum PortRuleMode {
    Multiport,
    PerPort,
}

impl PortRuleMode {
    pub(super) fn describe(self) -> &'static str {
        match self {
            Self::Multiport => "multiport",
            Self::PerPort => "per-port",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) enum RejectMode {
    RejectTcpReset,
    Reject,
    Drop,
}

impl RejectMode {
    pub(super) fn describe(self) -> &'static str {
        match self {
            Self::RejectTcpReset => "REJECT tcp-reset",
            Self::Reject => "REJECT",
            Self::Drop => "DROP",
        }
    }

    pub(super) fn push_args(self, args: &mut Vec<String>) {
        match self {
            Self::RejectTcpReset => {
                args.push("-j".into());
                args.push("REJECT".into());
                args.push("--reject-with".into());
                args.push("tcp-reset".into());
            }
            Self::Reject => {
                args.push("-j".into());
                args.push("REJECT".into());
            }
            Self::Drop => {
                args.push("-j".into());
                args.push("DROP".into());
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(super) struct ProxyRuleStrategy {
    pub(super) local_match: LocalMatchMode,
    pub(super) port_mode: PortRuleMode,
    pub(super) reject_mode: RejectMode,
}

impl ProxyRuleStrategy {
    pub(super) fn describe(self, backend: Backend, proto: TransportProtocol) -> String {
        format!(
            "backend={}, proto={}, local={}, ports={}, action={}",
            backend.label(),
            proto.name(),
            self.local_match.describe(backend),
            self.port_mode.describe(),
            self.reject_mode.describe()
        )
    }
}
