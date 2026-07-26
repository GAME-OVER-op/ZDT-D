//! Общий механизм сна демона.
//!
//! Задача: когда служба запущена, но к zdtd никто не обращается, фоновые потоки
//! не должны ни просыпаться по таймеру, ни запускать внешние команды. Сам демон
//! трафик не несёт: правила живут в ядре, а обработчики (nfqws, sing-box,
//! tun2socks и прочие) — отдельные процессы. Поэтому парковка его фоновых
//! потоков не влияет на защиту, а только отодвигает наблюдение.
//!
//! Механизм тот же, что уже используется в `internet_wait.rs`: ожидание на
//! условной переменной. Ядро снимает поток с планировщика (ноль процессорного
//! времени, ноль пробуждений), но поднимает его мгновенно, как только кто-то
//! вызвал `touch()` (обращение к API) или `wake_all()` (остановка, смена
//! настроек).
//!
//! IMPORTANT: здесь никогда не используется ожидание без таймаута. Даже
//! «парковка» на двадцать минут — это `wait_timeout`. Потерянный сигнал
//! пробуждения деградирует до задержки, а не до зависшего навсегда потока.
//!
//! IMPORTANT: признак «нас звали» — это счётчик под тем же мьютексом, который
//! держит условная переменная. Без этого возможна классическая гонка: сигнал
//! приходит в момент, когда поток ещё не вошёл в ожидание, и теряется.

use std::sync::{
    atomic::{AtomicU64, Ordering},
    Condvar, Mutex, OnceLock,
};
use std::time::{Duration, Instant};

/// Тишина дольше этого времени = к демону никто не обращается.
const IDLE_AFTER: Duration = Duration::from_secs(5 * 60);

static WAKE: OnceLock<(Mutex<u64>, Condvar)> = OnceLock::new();
static BASELINE: OnceLock<Instant> = OnceLock::new();
static LAST_ACTIVITY_MS: AtomicU64 = AtomicU64::new(0);

fn wake_slot() -> &'static (Mutex<u64>, Condvar) {
    WAKE.get_or_init(|| (Mutex::new(0), Condvar::new()))
}

/// Точка отсчёта для монотонных часов: `Instant` нельзя положить в атомарное
/// значение, поэтому активность храним как миллисекунды от старта.
fn baseline() -> Instant {
    *BASELINE.get_or_init(Instant::now)
}

fn now_ms() -> u64 {
    baseline().elapsed().as_millis() as u64
}

/// Текущее значение счётчика пробуждений.
pub fn wake_epoch() -> u64 {
    let (lock, _) = wake_slot();
    let guard = match lock.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    *guard
}

/// Отметить обращение к демону и поднять все спящие фоновые потоки.
pub fn touch() {
    LAST_ACTIVITY_MS.store(now_ms(), Ordering::SeqCst);
    wake_all();
}

/// Поднять спящие потоки без отметки активности: остановка, смена настроек,
/// смена режима наблюдателей.
pub fn wake_all() {
    let (lock, cvar) = wake_slot();
    {
        let mut epoch = match lock.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        *epoch = epoch.wrapping_add(1);
    }
    cvar.notify_all();
}

/// Сколько времени к демону никто не обращался.
fn idle_for() -> Duration {
    let last = LAST_ACTIVITY_MS.load(Ordering::SeqCst);
    Duration::from_millis(now_ms().saturating_sub(last))
}

/// Демон запущен, но им никто не пользуется.
pub fn is_idle() -> bool {
    idle_for() >= IDLE_AFTER
}

/// Ждать до `deadline`, но не дольше.
///
/// Возвращает `true`, если поток поднят обращением к демону или остановкой, и
/// `false`, если ожидание завершилось по времени. `seen` хранит последнее
/// известное значение счётчика пробуждений и обновляется на месте.
pub fn sleep_until(deadline: Instant, seen: &mut u64) -> bool {
    let (lock, cvar) = wake_slot();
    let mut guard = match lock.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    loop {
        if *guard != *seen {
            // Звали, пока мы шли сюда: в ожидание не заходим вовсе.
            *seen = *guard;
            return true;
        }
        let now = Instant::now();
        if now >= deadline {
            return false;
        }
        let waited = match cvar.wait_timeout(guard, deadline.saturating_duration_since(now)) {
            Ok(v) => v,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard = waited.0;
        // Ложное пробуждение futex: следующий круг перепроверит и счётчик, и дедлайн.
    }
}
