use serde::{de, Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;

use crate::iptables::iptables_port::ProtoChoice;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SingboxMode {
    T2s,
    Vpn,
}

impl Default for SingboxMode {
    fn default() -> Self { Self::T2s }
}

impl SingboxMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::T2s => "t2s",
            Self::Vpn => "vpn",
        }
    }

    pub fn is_t2s(&self) -> bool { matches!(self, Self::T2s) }
    pub fn is_vpn(&self) -> bool { matches!(self, Self::Vpn) }
}

impl Serialize for SingboxMode {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for SingboxMode {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = Option::<String>::deserialize(deserializer)?.unwrap_or_else(|| "t2s".to_string());
        match s.trim().to_ascii_lowercase().as_str() {
            "" | "t2s" | "proxy" => Ok(Self::T2s),
            "vpn" | "tun2socks" | "t2s-vpn" | "t2s_vpn" => Ok(Self::Vpn),
            other => Err(de::Error::custom(format!("unknown sing-box mode: {other}"))),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    #[serde(default)]
    pub mode: SingboxMode,
    #[serde(default)]
    pub t2s_port: u16,
    #[serde(default)]
    pub t2s_web_port: u16,
    #[serde(default)]
    pub tun: String,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default)]
    pub tun2socks_loglevel: String,
    #[serde(default)]
    pub proto_mode: String,
    #[serde(default = "default_endpoint_resolve")]
    pub endpoint_resolve: bool,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            mode: SingboxMode::T2s,
            t2s_port: 0,
            t2s_web_port: 0,
            tun: String::new(),
            dns: Vec::new(),
            tun2socks_loglevel: String::new(),
            proto_mode: String::new(),
            endpoint_resolve: true,
        }
    }
}

impl ProfileSetting {
    pub fn proto_choice(&self) -> ProtoChoice {
        match self.proto_mode.trim().to_ascii_lowercase().as_str() {
            "tcp" => ProtoChoice::Tcp,
            _ => ProtoChoice::TcpUdp,
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ServerSetting {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub sni: Option<String>,
    #[serde(default)]
    pub sni_options: Vec<String>,
}

impl Default for ServerSetting {
    fn default() -> Self {
        Self {
            enabled: false,
            port: 1080,
            sni: None,
            sni_options: Vec::new(),
        }
    }
}

fn default_endpoint_resolve() -> bool { true }

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    out.extend(crate::common::config::split_comma_whitespace(s));
                }
            }
        }
        Value::String(s) => out.extend(crate::common::config::split_comma_whitespace(&s)),
        Value::Null => {}
        _ => return Err(de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}



#[derive(Debug, Clone)]
pub(crate) struct ServerPlan {
    pub name: String,
    pub port: u16,
    pub config_path: std::path::PathBuf,
    pub log_path: std::path::PathBuf,
}

#[derive(Debug, Clone)]
pub(crate) struct T2sProfilePlan {
    pub name: String,
    pub setting: ProfileSetting,
    pub uid_out: std::path::PathBuf,
    pub uid_count: usize,
    pub needs_t2s: bool,
    pub t2s_log: std::path::PathBuf,
    pub servers: Vec<ServerPlan>,
}

#[derive(Debug, Clone)]
pub(crate) struct VpnProfilePlan {
    pub name: String,
    pub setting: ProfileSetting,
    pub server_name: String,
    pub server_port: u16,
    pub config_path: std::path::PathBuf,
    pub app_in: std::path::PathBuf,
    pub app_out: std::path::PathBuf,
    pub log_path: std::path::PathBuf,
    pub tun2socks_log_path: std::path::PathBuf,
    pub netid: u32,
    pub tun: String,
    pub tun_address: String,
    pub cidr: String,
    pub endpoint_escape_ips: Vec<String>,
}
