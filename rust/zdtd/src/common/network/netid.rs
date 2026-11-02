use anyhow::{bail, Result};


// IMPORTANT: единый реестр диапазонов netid для всех VPN-движков.
// Блоки обязаны не пересекаться: иначе профили разных программ получают один
// и тот же netid и дерутся за одну сеть netd (раньше mieru и amneziawg делили
// 25200..25999, а openvpn 20200..29999 перекрывал вообще все диапазоны).
// Непересечение проверяется тестом netid_blocks_do_not_overlap ниже.
pub const NETID_OPENVPN: (u32, u32) = (20200, 20999);
pub const NETID_TUN2SOCKS: (u32, u32) = (21200, 21999);
pub const NETID_SINGBOX: (u32, u32) = (22200, 22999);
pub const NETID_MYVPN: (u32, u32) = (23200, 23999);
pub const NETID_MIHOMO: (u32, u32) = (24200, 24999);
pub const NETID_MIERU: (u32, u32) = (25200, 25999);
pub const NETID_HYSTERIA2: (u32, u32) = (26200, 26999);
pub const NETID_AMNEZIAWG: (u32, u32) = (27200, 27999);
pub const NETID_SINGBOX_LX: (u32, u32) = (28200, 28999);

pub const NETID_BLOCKS: [(&str, (u32, u32)); 9] = [
    ("openvpn", NETID_OPENVPN),
    ("tun2socks", NETID_TUN2SOCKS),
    ("singbox", NETID_SINGBOX),
    ("myvpn", NETID_MYVPN),
    ("mihomo", NETID_MIHOMO),
    ("mieru", NETID_MIERU),
    ("hysteria2", NETID_HYSTERIA2),
    ("amneziawg", NETID_AMNEZIAWG),
    ("singbox-lx", NETID_SINGBOX_LX),
];

// merged: было generate_netid(), выдававшее первый свободный id по порядку включённых
// профилей. Теперь id зависит только от позиции профиля в полном списке профилей
// движка (включая выключенные), поэтому включение/выключение одного профиля больше
// не сдвигает netid и производную от него подсеть туннеля у соседей.
pub fn stable_netid(base: u32, max: u32, all_profiles: &[String], profile: &str) -> Result<u32> {
    let index = all_profiles
        .iter()
        .position(|name| name == profile)
        .ok_or_else(|| anyhow::anyhow!("profile {profile} is not registered in active.json"))?;
    let index = u32::try_from(index).unwrap_or(u32::MAX);
    let netid = base
        .checked_add(index)
        .ok_or_else(|| anyhow::anyhow!("netid overflow for profile {profile}"))?;
    if netid > max {
        bail!("no free netid in range {base}..={max} for profile {profile} (index {index})");
    }
    Ok(netid)
}

#[cfg(test)]
mod netid_tests {
    use super::*;

    #[test]
    fn netid_blocks_do_not_overlap() {
        for (i, (a_name, (a_base, a_max))) in NETID_BLOCKS.iter().enumerate() {
            assert!(a_base <= a_max, "{a_name}: base > max");
            for (b_name, (b_base, b_max)) in NETID_BLOCKS.iter().skip(i + 1) {
                assert!(
                    a_max < b_base || b_max < a_base,
                    "netid blocks overlap: {a_name} and {b_name}"
                );
            }
        }
    }

    #[test]
    fn stable_netid_is_position_based() {
        let names = vec!["a".to_string(), "b".to_string(), "c".to_string()];
        assert_eq!(stable_netid(100, 199, &names, "a").unwrap(), 100);
        assert_eq!(stable_netid(100, 199, &names, "c").unwrap(), 102);
        assert!(stable_netid(100, 101, &names, "c").is_err());
        assert!(stable_netid(100, 199, &names, "zzz").is_err());
    }
}
