use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub enum ScreenProbe {
    FbBlank(PathBuf),
    BacklightBright(PathBuf),
    BacklightPower(PathBuf),
}

pub fn detect_screen_probe() -> Option<ScreenProbe> {
    let fb_blank = PathBuf::from("/sys/class/graphics/fb0/blank");
    if fb_blank.exists() {
        return Some(ScreenProbe::FbBlank(fb_blank));
    }

    let bl_dir = Path::new("/sys/class/backlight");
    if let Ok(entries) = fs::read_dir(bl_dir) {
        for ent in entries.flatten() {
            let p = ent.path();
            let bright = p.join("brightness");
            if bright.exists() {
                return Some(ScreenProbe::BacklightBright(bright));
            }
            let blp = p.join("bl_power");
            if blp.exists() {
                return Some(ScreenProbe::BacklightPower(blp));
            }
        }
    }
    None
}

pub fn raw_screen_on(probe: &ScreenProbe) -> bool {
    match probe {
        ScreenProbe::FbBlank(p) => read_i32(p).map(|v| v == 0).unwrap_or(true),
        ScreenProbe::BacklightBright(p) => read_i32(p).map(|v| v > 0).unwrap_or(true),
        ScreenProbe::BacklightPower(p) => read_i32(p).map(|v| v == 0).unwrap_or(true),
    }
}

fn read_i32(path: &Path) -> Option<i32> {
    fs::read_to_string(path)
        .ok()
        .and_then(|s| s.trim().parse::<i32>().ok())
}
