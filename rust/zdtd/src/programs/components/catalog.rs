use serde_json::{json, Value};
use std::{os::unix::fs::PermissionsExt, path::Path};

const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

#[derive(Clone, Copy)]
struct ComponentDef {
    id: &'static str,
    asset: &'static str,
    files: &'static [&'static str],
    program_ids: &'static [&'static str],
}

const COMPONENTS: &[ComponentDef] = &[
    ComponentDef { id: "nfqws", asset: "nfqws.zip", files: &["nfqws"], program_ids: &["nfqws"] },
    ComponentDef { id: "nfqws2", asset: "nfqws2.zip", files: &["nfqws2"], program_ids: &["nfqws2"] },
    ComponentDef { id: "byedpi", asset: "byedpi.zip", files: &["byedpi"], program_ids: &["byedpi"] },
    ComponentDef { id: "dpitunnel", asset: "dpitunnel.zip", files: &["dpitunnel-cli"], program_ids: &["dpitunnel"] },
    ComponentDef { id: "operaproxy", asset: "opera_proxy.zip", files: &["opera-proxy", "byedpi"], program_ids: &["operaproxy"] },
    ComponentDef { id: "sing-box", asset: "sing_box.zip", files: &["sing-box"], program_ids: &["sing-box"] },
    ComponentDef { id: "sing-box-lx", asset: "sing_box_lx.zip", files: &["sing-box-lx"], program_ids: &["sing-box-lx"] },
    ComponentDef { id: "hysteria2", asset: "hysteria2.zip", files: &["hysteria2"], program_ids: &["hysteria2"] },
    ComponentDef { id: "wireproxy", asset: "wireproxy.zip", files: &["wireproxy"], program_ids: &["wireproxy"] },
    ComponentDef { id: "openvpn", asset: "openvpn.zip", files: &["openvpn"], program_ids: &["openvpn"] },
    ComponentDef { id: "amneziawg", asset: "amneziawg.zip", files: &["amneziawg-go", "awg"], program_ids: &["amneziawg"] },
    ComponentDef { id: "mihomo", asset: "mihomo.zip", files: &["mihomo"], program_ids: &["mihomo"] },
    ComponentDef { id: "mieru", asset: "mieru.zip", files: &["mieru"], program_ids: &["mieru"] },
    ComponentDef { id: "tor", asset: "tor.zip", files: &["torproxy", "lyrebird"], program_ids: &["tor"] },
    ComponentDef { id: "tgwsproxy", asset: "tg_ws_proxy.zip", files: &["tg-ws-proxy"], program_ids: &["tgwsproxy"] },
];

fn executable(name: &str) -> bool {
    // ByeDPI historically had several accepted executable names. Treat an existing legacy
    // executable as the component being installed so an older user installation does not get
    // a false "missing" card after updating the app/daemon.
    if name == "byedpi" {
        return ["byedpi", "ciadpi-zdt", "ciadpi", "bye_dpi"].iter().any(|candidate| executable_exact(candidate));
    }
    executable_exact(name)
}

fn executable_exact(name: &str) -> bool {
    let path = Path::new(BIN_DIR).join(name);
    let Ok(meta) = path.metadata() else { return false; };
    meta.is_file() && (meta.permissions().mode() & 0o111 != 0)
}


pub fn is_program_installed(program_id: &str) -> bool {
    COMPONENTS
        .iter()
        .find(|component| component.program_ids.iter().any(|id| *id == program_id))
        .map(|component| component.files.iter().all(|name| executable(name)))
        .unwrap_or(true)
}

pub fn collect() -> Value {
    let components = COMPONENTS.iter().map(|component| {
        let missing: Vec<&str> = component.files.iter().copied().filter(|name| !executable(name)).collect();
        json!({
            "id": component.id,
            "installed": missing.is_empty(),
            "asset": component.asset,
            "files": component.files,
            "missing_files": missing,
            "program_ids": component.program_ids,
        })
    }).collect::<Vec<_>>();

    json!({
        "ok": true,
        "schema_version": 1,
        "components": components,
    })
}
