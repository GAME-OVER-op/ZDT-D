use super::registry::{descriptor, ProgramDescriptor};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ProgramId {
    Nfqws,
    Nfqws2,
    ByeDpi,
    DpiTunnel,
    DnsCrypt,
    OperaProxy,
    TgwsProxy,
    SingBox,
    SingBoxLx,
    Hysteria2,
    WireProxy,
    Tor,
    MyProxy,
    MyProgram,
    OpenVpn,
    AmneziaWg,
    Tun2Socks,
    MyVpn,
    Mihomo,
    Mieru,
    ProxyInfo,
    BlockedQuic,
}

impl ProgramId {
    pub fn descriptor(self) -> &'static ProgramDescriptor {
        descriptor(self)
    }

    pub fn canonical(self) -> &'static str {
        self.descriptor().canonical_id
    }

    pub fn api_id(self) -> &'static str {
        self.descriptor().api_id
    }

    pub fn display_name(self) -> &'static str {
        self.descriptor().display_name
    }

    pub fn working_dir(self) -> &'static str {
        self.descriptor().working_dir
    }

    pub fn process_name(self) -> Option<&'static str> {
        self.descriptor().process_name
    }

    pub fn parse(raw: &str) -> Option<Self> {
        let raw = raw.trim();
        if raw.is_empty() {
            return None;
        }
        super::registry::PROGRAMS
            .iter()
            .find(|desc| {
                desc.canonical_id.eq_ignore_ascii_case(raw)
                    || desc.api_id.eq_ignore_ascii_case(raw)
                    || desc.aliases.iter().any(|alias| alias.eq_ignore_ascii_case(raw))
            })
            .map(|desc| desc.id)
    }
}
