use anyhow::{Context, Result};
use serde::Serialize;
use std::{fs, path::{Path, PathBuf}};

pub const SHARED_TOKEN_FILE: &str = "/data/adb/modules/ZDT-D/api/token";

#[derive(Clone, Debug, Serialize)]
pub struct InstanceMeta {
    pub schema_version: u32,
    pub api_name: String,
    pub api_version: u32,
    pub instance_id: String,
    pub program: String,
    pub profile: String,
    pub scope: String,
    pub pid: u32,
    pub started_at: u64,
    pub updated_at: u64,
    pub web_addr: String,
    pub web_port: u16,
    pub listen_addr: String,
    pub listen_port: u16,
    pub external_port: u16,
    pub backend_mode: String,
    pub priority_speed_aware: bool,
    pub token_file: String,
}

#[derive(Clone, Debug)]
pub struct ApiRuntime {
    pub api_dir: PathBuf,
    pub token_file: PathBuf,
    pub token: Option<String>,
    pub instance: InstanceMeta,
}

impl ApiRuntime {
    pub fn new(args: &crate::cli::Args, started_at: u64) -> Result<Self> {
        let api_dir = PathBuf::from(args.api_dir.trim()).join("t2s");
        let token_file = PathBuf::from(SHARED_TOKEN_FILE);
        let token = fs::read_to_string(&token_file)
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        if token.is_none() {
            tracing::warn!("t2s extended API auth disabled: missing {}", token_file.display());
        }

        let pid = std::process::id();
        let program = args.program.trim().to_string();
        let profile = args.profile.trim().to_string();
        let scope = if !args.scope.trim().is_empty() {
            args.scope.trim().to_string()
        } else if !program.is_empty() && !profile.is_empty() {
            format!("profile/{}/{}", program, profile)
        } else if !program.is_empty() {
            format!("program/{}", program)
        } else {
            format!("t2s/{}", args.web_port)
        };
        let instance_id = if !args.instance_id.trim().is_empty() {
            sanitize_id(args.instance_id.trim())
        } else {
            sanitize_id(&format!("t2s_{}_{}_{}", args.web_port, pid, scope))
        };
        let instance = InstanceMeta {
            schema_version: 1,
            api_name: "t2s".to_string(),
            api_version: 1,
            instance_id,
            program,
            profile,
            scope,
            pid,
            started_at,
            updated_at: started_at,
            web_addr: args.web_addr.clone(),
            web_port: args.web_port,
            listen_addr: args.listen_addr.clone(),
            listen_port: args.listen_port,
            external_port: args.external_port,
            backend_mode: format!("{:?}", args.backend_mode).to_ascii_lowercase(),
            priority_speed_aware: args.priority_speed_aware,
            token_file: SHARED_TOKEN_FILE.to_string(),
        };
        let rt = Self { api_dir, token_file, token, instance };
        if let Err(e) = rt.write_metadata() {
            tracing::warn!("failed to write t2s API metadata: {:#}", e);
        }
        Ok(rt)
    }

    pub fn is_authorized(&self, headers: &axum::http::HeaderMap) -> bool {
        let Some(token) = self.token.as_deref().filter(|s| !s.is_empty()) else {
            return false;
        };
        if let Some(v) = headers.get("x-api-key").and_then(|v| v.to_str().ok()) {
            if v.trim() == token {
                return true;
            }
        }
        if let Some(v) = headers.get("authorization").and_then(|v| v.to_str().ok()) {
            let v = v.trim();
            if let Some(rest) = v.strip_prefix("Bearer ") {
                return rest.trim() == token;
            }
        }
        false
    }

    pub fn refreshed_instance(&self) -> InstanceMeta {
        let mut meta = self.instance.clone();
        meta.updated_at = crate::stats::now_ts();
        meta
    }

    pub fn write_metadata(&self) -> Result<()> {
        fs::create_dir_all(self.instances_dir()).with_context(|| format!("mkdir {}", self.instances_dir().display()))?;
        fs::create_dir_all(self.ports_dir()).with_context(|| format!("mkdir {}", self.ports_dir().display()))?;
        let meta = self.refreshed_instance();
        let instance_path = self.instance_file();
        write_json_atomic_struct(&instance_path, &meta)?;
        let port_index = serde_json::json!({
            "schema_version": 1,
            "api_name": "t2s",
            "api_version": 1,
            "web_port": meta.web_port,
            "instance_id": meta.instance_id,
            "instance_file": instance_path.to_string_lossy(),
            "base_url": format!("{}{}:{}", "http://", meta.web_addr, meta.web_port),
            "scope": meta.scope,
            "program": meta.program,
            "profile": meta.profile,
            "pid": meta.pid,
            "updated_at": meta.updated_at,
            "token_file": SHARED_TOKEN_FILE,
        });
        write_json_atomic(&self.port_file(), &port_index)?;
        let info = serde_json::json!({
            "schema_version": 1,
            "api_name": "t2s",
            "api_version": 1,
            "api_dir": self.api_dir.to_string_lossy(),
            "token_file": SHARED_TOKEN_FILE,
            "public_web": true,
            "extended_api_prefix": "/api/v1",
            "instances_dir": self.instances_dir().to_string_lossy(),
            "ports_dir": self.ports_dir().to_string_lossy(),
            "updated_at": meta.updated_at,
        });
        write_json_atomic(&self.api_dir.join("info.json"), &info)?;
        Ok(())
    }

    pub fn cleanup_metadata(&self) {
        let _ = fs::remove_file(self.instance_file());
        let _ = fs::remove_file(self.port_file());
    }

    fn instances_dir(&self) -> PathBuf { self.api_dir.join("instances") }
    fn ports_dir(&self) -> PathBuf { self.api_dir.join("ports") }
    fn instance_file(&self) -> PathBuf { self.instances_dir().join(format!("{}.json", self.instance.instance_id)) }
    fn port_file(&self) -> PathBuf { self.ports_dir().join(format!("{}.json", self.instance.web_port)) }
}

fn sanitize_id(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for c in raw.chars() {
        if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
            out.push(c);
        } else {
            out.push('_');
        }
    }
    let trimmed = out.trim_matches('_');
    if trimmed.is_empty() { "t2s".to_string() } else { trimmed.chars().take(120).collect() }
}

fn write_json_atomic(path: &Path, value: &serde_json::Value) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = path.with_extension("tmp");
    let txt = serde_json::to_string_pretty(value)?;
    fs::write(&tmp, txt.as_bytes()).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

fn write_json_atomic_struct<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let json = serde_json::to_value(value)?;
    write_json_atomic(path, &json)
}
