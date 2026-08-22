//! DirectInput backend: drives any PID force-feedback wheel (MOZA R-series
//! and Simagic Alpha EVO bases verified as standard DirectInput constant-
//! force devices; Simagic needs SimPro Manager installed, its FFB gain at
//! 100%, and `--rated-torque` — see device.rs) through the classic recipe — enumerate FFB devices, exclusive+background
//! acquisition on a hidden window, autocenter off, one constant-force effect
//! whose magnitude is updated in place (`DIEP_TYPESPECIFICPARAMS |
//! DIEP_NORESTART`, the irFFB/SDL-proven path — never recreate the effect per
//! update).
//!
//! Safety posture (hardened after adversarial review):
//! - The effect runs with a FINITE duration; while torque is nonzero it is
//!   re-triggered well before expiry — if this process hangs or dies, the
//!   hardware self-zeroes without another line of code executing. It is
//!   NEVER played while the confirmed magnitude is zero, and never re-played
//!   while parameters are unconfirmed — so a zero that a driver accepted but
//!   the async transport lost still decays within one lease instead of being
//!   replayed, and stale parameters can never be re-started.
//! - Effect parameters live in owned, stable storage for the device's whole
//!   lifetime (DirectInput does not promise to copy `lpvTypeSpecificParams`).
//! - The magnitude cache updates ONLY on a successful write; a failed ZERO
//!   write escalates to `Stop` + `SendForceFeedbackCommand(STOPALL)` and
//!   keeps retrying — a stale force must never keep playing silently.
//! - Acquisition loss re-runs the property gate: re-acquire attempts are
//!   throttled to ~1 Hz (mirrors evdev's REOPEN_EVERY), and DIPROP_RANGE +
//!   DIPROP_AUTOCENTER are RE-APPLIED (while unacquired — DirectInput
//!   refuses property writes on an acquired device) before the Acquire that
//!   would let the effect leave quarantine: a driver may restore autocenter
//!   across a re-acquire, which is torque outside every software clamp.
//! - Rated torque is explicit: USB VID/PID first (DIPROP_VIDPID against the
//!   shared MOZA table in device.rs — names are localizable, ids are not),
//!   name matching second, `--rated-torque` required otherwise (hardcoding
//!   9 Nm on a 20 Nm base would double every "cap").
//!
//! Numeric dinput.h constants are defined locally (they are stable ABI);
//! structs/interfaces come from the `windows` crate.

#![allow(clippy::upper_case_acronyms)]

use crate::device::{
    rated_by_usb_id, resolve_rated_nm, resolve_rated_nm_with_usb_id, FfbDevice, WheelState,
};
use std::ffi::c_void;
use std::time::{Duration, Instant};
use windows::core::{w, Interface, GUID};
use windows::Win32::Devices::HumanInterfaceDevice::{
    DirectInput8Create, IDirectInput8W, IDirectInputDevice8W, IDirectInputEffect,
    DICONSTANTFORCE, DIDATAFORMAT, DIDEVICEINSTANCEW, DIEFFECT, DIOBJECTDATAFORMAT,
    DIPROPDWORD, DIPROPHEADER, DIPROPRANGE,
};
use windows::Win32::Foundation::{BOOL, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, PeekMessageW, RegisterClassW,
    TranslateMessage, CS_OWNDC, MSG, PM_REMOVE, WINDOW_EX_STYLE, WNDCLASSW, WS_OVERLAPPED,
};

const DIRECTINPUT_VERSION: u32 = 0x0800;
const DI8DEVCLASS_GAMECTRL: u32 = 4;
const DIEDFL_ATTACHEDONLY: u32 = 0x0000_0001;
const DIEDFL_FORCEFEEDBACK: u32 = 0x0000_0100;
const DIENUM_CONTINUE: BOOL = BOOL(1);

const DIDF_ABSAXIS: u32 = 0x0000_0001;
const DIDFT_AXIS: u32 = 0x0000_0003;
const DIDFT_BUTTON: u32 = 0x0000_000C;
const DIDFT_ANYINSTANCE: u32 = 0x00FF_FF00;
const DIDFT_OPTIONAL: u32 = 0x8000_0000;

const DISCL_EXCLUSIVE: u32 = 0x1;
const DISCL_BACKGROUND: u32 = 0x8;

const DIPH_DEVICE: u32 = 0;
const DIPH_BYOFFSET: u32 = 1;
/// dinput.h MAKEDIPROP(n): property "GUIDs" are small integers by ABI.
const DIPROP_RANGE: *const GUID = 4 as *const GUID;
const DIPROP_AUTOCENTER: *const GUID = 9 as *const GUID;
/// Read-only DIPROPDWORD: wVid = LOWORD(dwData), wPid = HIWORD(dwData).
const DIPROP_VIDPID: *const GUID = 24 as *const GUID;
const DIPROPAUTOCENTER_OFF: u32 = 0;

const DI_FFNOMINALMAX: i32 = 10_000;
const DIEFF_CARTESIAN: u32 = 0x10;
const DIEFF_OBJECTOFFSETS: u32 = 0x02;
const DIEB_NOTRIGGER: u32 = 0xFFFF_FFFF;
const DIEP_TYPESPECIFICPARAMS: u32 = 0x0100;
const DIEP_NORESTART: u32 = 0x4000_0000;
const DISFFC_STOPALL: u32 = 0x2;

const DIERR_INPUTLOST: i32 = 0x8007_001Eu32 as i32;
const DIERR_NOTACQUIRED: i32 = 0x8007_000Cu32 as i32;

const GUID_XAXIS: GUID = GUID::from_u128(0xA36D02E0_C9F3_11CF_BFC7_444553540000);
const GUID_CONSTANT_FORCE: GUID = GUID::from_u128(0x13541C20_8E33_11D0_9AD0_00A0C9A06E35);

/// Effect duration (µs) and re-trigger cadence: the hardware self-zeroes
/// ≤250 ms after this process stops executing, whatever the reason.
const EFFECT_DURATION_US: u32 = 250_000;
const EFFECT_RETRIGGER_EVERY: Duration = Duration::from_millis(100);
/// Throttle re-acquire attempts after acquisition loss to ~1 Hz (mirrors
/// the evdev backend's REOPEN_EVERY) — an unplugged base doesn't need 250
/// Acquire calls per second.
const REACQUIRE_EVERY: Duration = Duration::from_secs(1);

/// Wire format for GetDeviceState: one absolute axis + 32 buttons.
#[repr(C)]
#[derive(Default, Clone, Copy)]
struct RawState {
    lx: i32,
    buttons: [u8; 32],
}

pub struct DirectInputDevice {
    _di: IDirectInput8W,
    device: IDirectInputDevice8W,
    effect: IDirectInputEffect,
    hwnd: HWND,
    name: String,
    id_hash: u32,
    range_deg: f32,
    rated_nm: f32,
    /// Kept for the re-acquire path, which re-runs the autocenter gate.
    ack_autocenter: bool,
    prev_deg: f32,
    vel_deg_per_s: f32,
    /// Suppress the velocity sample right after (re)acquisition — a stale
    /// prev_deg against a fresh angle would synthesize an enormous spike.
    skip_vel_sample: bool,
    output_fault: bool,
    /// Owned, stable storage for the effect parameters — DirectInput keeps
    /// the pointer for the effect's lifetime (adversarial finding: stack
    /// locals here are a dangling-pointer torque hazard).
    cf: Box<DICONSTANTFORCE>,
    _axes: Box<[u32; 1]>,
    _direction: Box<[i32; 1]>,
    last_magnitude: i32,
    /// True only while the last parameter write is KNOWN accepted. While
    /// false the effect is QUARANTINED: never played, and the only permitted
    /// write is one that forces a known zero (a Start would replay whatever
    /// stale parameters the device still stores).
    params_valid: bool,
    needs_start: bool,
    effect_started_at: Option<Instant>,
    /// Throttles play retries after a failed Start so a marginal driver
    /// isn't hammered at loop rate; the first Start after a fresh parameter
    /// write is never delayed by it (the previous attempt is long past).
    last_play_attempt: Option<Instant>,
    /// Throttles re-acquire attempts to REACQUIRE_EVERY while the device is
    /// lost. Cleared on recovery so the next loss retries immediately.
    last_reacquire_attempt: Option<Instant>,
    /// True between detecting acquisition loss and recovering it, so both
    /// transitions bump the epoch exactly once.
    connection_lost: bool,
    /// Bumped on acquisition loss AND on recovery; the bridge disarms on any
    /// change so forces only resume after a deliberate client re-engage.
    epoch: u32,
}

struct EnumCtx {
    devices: Vec<(GUID, String)>,
}

unsafe extern "system" fn enum_cb(inst: *mut DIDEVICEINSTANCEW, ctx: *mut c_void) -> BOOL {
    let ctx = &mut *(ctx as *mut EnumCtx);
    let inst = &*inst;
    let name = String::from_utf16_lossy(
        &inst.tszProductName[..inst
            .tszProductName
            .iter()
            .position(|&c| c == 0)
            .unwrap_or(inst.tszProductName.len())],
    );
    ctx.devices.push((inst.guidInstance, name));
    DIENUM_CONTINUE
}

fn create_di() -> windows::core::Result<IDirectInput8W> {
    unsafe {
        let hinst: windows::Win32::Foundation::HINSTANCE = GetModuleHandleW(None)?.into();
        let mut out: Option<IDirectInput8W> = None;
        DirectInput8Create(
            hinst,
            DIRECTINPUT_VERSION,
            &IDirectInput8W::IID,
            &mut out as *mut _ as *mut *mut c_void,
            None,
        )?;
        out.ok_or_else(windows::core::Error::from_win32)
    }
}

fn enumerate(di: &IDirectInput8W) -> windows::core::Result<Vec<(GUID, String)>> {
    let mut ctx = EnumCtx { devices: Vec::new() };
    unsafe {
        di.EnumDevices(
            DI8DEVCLASS_GAMECTRL,
            Some(enum_cb),
            &mut ctx as *mut _ as *mut c_void,
            DIEDFL_ATTACHEDONLY | DIEDFL_FORCEFEEDBACK,
        )?;
    }
    Ok(ctx.devices)
}

pub fn list_devices() -> windows::core::Result<Vec<String>> {
    let di = create_di()?;
    Ok(enumerate(&di)?.into_iter().map(|(_, n)| n).collect())
}

unsafe extern "system" fn wndproc(h: HWND, m: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    DefWindowProcW(h, m, w, l)
}

/// Exclusive-mode FFB needs a real (hidden) top-level window to cooperate on.
fn hidden_window() -> windows::core::Result<HWND> {
    unsafe {
        let hinst = GetModuleHandleW(None)?;
        let class = WNDCLASSW {
            style: CS_OWNDC,
            lpfnWndProc: Some(wndproc),
            hInstance: hinst.into(),
            lpszClassName: w!("SimwheelBridgeHidden"),
            ..Default::default()
        };
        RegisterClassW(&class);
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            w!("SimwheelBridgeHidden"),
            w!("simwheel-bridge"),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            None,
            None,
            hinst,
            None,
        )?;
        Ok(hwnd)
    }
}

fn pump_messages(hwnd: HWND) {
    unsafe {
        let mut msg = MSG::default();
        while PeekMessageW(&mut msg, hwnd, 0, 0, PM_REMOVE).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

/// Read a whole-device DIPROPDWORD property (e.g. DIPROP_VIDPID).
fn get_dword_property(device: &IDirectInputDevice8W, prop: *const GUID) -> Option<u32> {
    let mut dw = DIPROPDWORD {
        diph: DIPROPHEADER {
            dwSize: std::mem::size_of::<DIPROPDWORD>() as u32,
            dwHeaderSize: std::mem::size_of::<DIPROPHEADER>() as u32,
            dwObj: 0,
            dwHow: DIPH_DEVICE,
        },
        dwData: 0,
    };
    // SAFETY: dw matches the property's documented DIPROPDWORD shape.
    unsafe { device.GetProperty(prop, &mut dw.diph).ok().map(|()| dw.dwData) }
}

/// Apply the device properties a driver may silently reset across an
/// acquisition loss: the symmetric full-scale axis range (the degree mapping
/// depends on it) and autocenter OFF (a restored autocenter is torque
/// OUTSIDE every software clamp). Called at open AND before every
/// re-Acquire — property writes need an unacquired device, so on the
/// recovery path this runs first, and the effect cannot leave quarantine
/// until after the Acquire that follows it.
fn apply_device_properties(
    device: &IDirectInputDevice8W,
    ack_autocenter: bool,
) -> Result<(), String> {
    unsafe {
        // Symmetric full-scale range so degree mapping has no ±1-LSB skew.
        let mut range = DIPROPRANGE {
            diph: DIPROPHEADER {
                dwSize: std::mem::size_of::<DIPROPRANGE>() as u32,
                dwHeaderSize: std::mem::size_of::<DIPROPHEADER>() as u32,
                dwObj: 0,
                dwHow: DIPH_BYOFFSET,
            },
            lMin: -32767,
            lMax: 32767,
        };
        device
            .SetProperty(DIPROP_RANGE, &mut range.diph)
            .map_err(|e| format!("cannot set the axis range: {e}"))?;

        // The base must not fight the game with its own centering spring.
        let mut autocenter = DIPROPDWORD {
            diph: DIPROPHEADER {
                dwSize: std::mem::size_of::<DIPROPDWORD>() as u32,
                dwHeaderSize: std::mem::size_of::<DIPROPHEADER>() as u32,
                dwObj: 0,
                dwHow: DIPH_DEVICE,
            },
            dwData: DIPROPAUTOCENTER_OFF,
        };
        if device
            .SetProperty(DIPROP_AUTOCENTER, &mut autocenter.diph)
            .is_err()
        {
            eprintln!(
                "[bridge] !!! could not disable device autocenter via DirectInput.\n\
                 [bridge] !!! If the base's own centering is active it will fight and\n\
                 [bridge] !!! add to game torque OUTSIDE every software clamp."
            );
            if !ack_autocenter {
                return Err(
                    "refusing to drive this device: verify centering is disabled in the \
                     vendor tool (MOZA Pit House / Simagic SimPro Manager: in-base \
                     spring, damper and centering at 0), then re-run with \
                     --ack-autocenter"
                        .into(),
                );
            }
            eprintln!("[bridge] continuing on --ack-autocenter (operator-acknowledged)");
        }
        Ok(())
    }
}

impl DirectInputDevice {
    pub fn open(
        index: usize,
        range_deg: f32,
        rated_nm: Option<f32>,
        ack_autocenter: bool,
    ) -> Result<Self, String> {
        let di = create_di().map_err(|e| e.to_string())?;
        let devices = enumerate(&di).map_err(|e| e.to_string())?;
        let (guid, name) = devices
            .get(index)
            .cloned()
            .ok_or_else(|| format!("no FFB device at index {index} (see --list)"))?;

        let hwnd = hidden_window().map_err(|e| e.to_string())?;
        unsafe {
            let mut device: Option<IDirectInputDevice8W> = None;
            di.CreateDevice(&guid, &mut device, None)
                .map_err(|e| e.to_string())?;
            let device = device.ok_or("CreateDevice returned nothing")?;

            // Rated torque: explicit flag first; then USB VID/PID against
            // the shared MOZA table (names are localizable and
            // driver-dependent, ids are not); then name matching; else a
            // hard error demanding --rated-torque. A Simagic id/name is
            // recognized but still errs — their ids are shared across
            // ratings, so the tailored message hands over the exact flag.
            let rated_nm = match rated_nm {
                Some(nm) => nm,
                None => {
                    let vidpid = get_dword_property(&device, DIPROP_VIDPID);
                    let by_id = vidpid
                        .and_then(|dw| rated_by_usb_id((dw & 0xFFFF) as u16, (dw >> 16) as u16));
                    match (by_id, vidpid) {
                        (Some(nm), _) => nm,
                        (None, Some(dw)) => resolve_rated_nm_with_usb_id(
                            &name, (dw & 0xFFFF) as u16, (dw >> 16) as u16, None)?,
                        (None, None) => resolve_rated_nm(&name, None)?,
                    }
                }
            };

            // Minimal data format: X axis at offset 0, 32 optional buttons.
            let mut objects = [DIOBJECTDATAFORMAT::default(); 33];
            objects[0] = DIOBJECTDATAFORMAT {
                pguid: &GUID_XAXIS,
                dwOfs: 0,
                dwType: DIDFT_AXIS | DIDFT_ANYINSTANCE,
                dwFlags: 0,
            };
            for (i, obj) in objects.iter_mut().enumerate().skip(1) {
                *obj = DIOBJECTDATAFORMAT {
                    pguid: std::ptr::null(),
                    dwOfs: (4 + (i - 1)) as u32,
                    dwType: DIDFT_BUTTON | DIDFT_ANYINSTANCE | DIDFT_OPTIONAL,
                    dwFlags: 0,
                };
            }
            let mut format = DIDATAFORMAT {
                dwSize: std::mem::size_of::<DIDATAFORMAT>() as u32,
                dwObjSize: std::mem::size_of::<DIOBJECTDATAFORMAT>() as u32,
                dwFlags: DIDF_ABSAXIS,
                dwDataSize: std::mem::size_of::<RawState>() as u32,
                dwNumObjs: objects.len() as u32,
                rgodf: objects.as_mut_ptr(),
            };
            device.SetDataFormat(&mut format).map_err(|e| e.to_string())?;
            device
                .SetCooperativeLevel(hwnd, DISCL_EXCLUSIVE | DISCL_BACKGROUND)
                .map_err(|e| e.to_string())?;

            // Range + autocenter — shared with the re-acquire path, which
            // must re-apply both (a driver may restore autocenter).
            apply_device_properties(&device, ack_autocenter)?;

            device.Acquire().map_err(|e| e.to_string())?;

            // The one constant-force effect: FINITE duration, re-triggered by
            // ensure_effect_running — parameters in owned storage.
            let mut cf = Box::new(DICONSTANTFORCE { lMagnitude: 0 });
            let mut axes = Box::new([0u32]); // object offset 0 = our X axis
            let mut direction = Box::new([0i32]); // sign lives in the magnitude
            let mut eff = DIEFFECT {
                dwSize: std::mem::size_of::<DIEFFECT>() as u32,
                dwFlags: DIEFF_CARTESIAN | DIEFF_OBJECTOFFSETS,
                dwDuration: EFFECT_DURATION_US,
                dwSamplePeriod: 0,
                dwGain: DI_FFNOMINALMAX as u32,
                dwTriggerButton: DIEB_NOTRIGGER,
                dwTriggerRepeatInterval: 0,
                cAxes: 1,
                rgdwAxes: axes.as_mut_ptr(),
                rglDirection: direction.as_mut_ptr(),
                lpEnvelope: std::ptr::null_mut(),
                cbTypeSpecificParams: std::mem::size_of::<DICONSTANTFORCE>() as u32,
                lpvTypeSpecificParams: &mut *cf as *mut _ as *mut c_void,
                dwStartDelay: 0,
            };
            let mut effect: Option<IDirectInputEffect> = None;
            device
                .CreateEffect(&GUID_CONSTANT_FORCE, &mut eff, &mut effect, None)
                .map_err(|e| e.to_string())?;
            let effect = effect.ok_or("CreateEffect returned nothing")?;

            let mut hasher: u32 = 0x811C_9DC5; // FNV-1a over the instance GUID
            for b in guid.to_u128().to_le_bytes() {
                hasher ^= b as u32;
                hasher = hasher.wrapping_mul(0x0100_0193);
            }

            let dev = DirectInputDevice {
                _di: di,
                device,
                effect,
                hwnd,
                name,
                id_hash: hasher,
                range_deg,
                rated_nm,
                ack_autocenter,
                prev_deg: 0.0,
                vel_deg_per_s: 0.0,
                skip_vel_sample: true,
                output_fault: false,
                cf,
                _axes: axes,
                _direction: direction,
                last_magnitude: 0,
                params_valid: true,
                needs_start: false,
                effect_started_at: None,
                last_play_attempt: None,
                last_reacquire_attempt: None,
                connection_lost: false,
                epoch: 0,
            };
            // The zero-magnitude effect is created (downloaded) but NOT
            // played: a zero needs no playing effect, and never playing at
            // zero is what makes the finite lease the zero-state guarantee.
            Ok(dev)
        }
    }

    /// The retrigger/recovery loop, run every poll.
    ///
    /// - Quarantined (`!params_valid`): the ONLY permitted action is forcing
    ///   the parameters to a known ZERO — never Start, which would replay
    ///   whatever stale parameters the device stores (review-caught).
    /// - Confirmed zero: nothing to play — let the finite lease lapse.
    /// - Confirmed nonzero: (re)trigger ahead of expiry. Start doubles as
    ///   the re-download path after acquisition loss.
    fn ensure_effect_running(&mut self) {
        if !self.params_valid {
            unsafe {
                self.cf.lMagnitude = 0;
                let mut eff = DIEFFECT {
                    dwSize: std::mem::size_of::<DIEFFECT>() as u32,
                    cbTypeSpecificParams: std::mem::size_of::<DICONSTANTFORCE>() as u32,
                    lpvTypeSpecificParams: &mut *self.cf as *mut _ as *mut c_void,
                    ..Default::default()
                };
                if self
                    .effect
                    .SetParameters(&mut eff, DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART)
                    .is_ok()
                {
                    self.last_magnitude = 0;
                    self.params_valid = true;
                    self.needs_start = false;
                    self.effect_started_at = None;
                    self.last_play_attempt = None;
                    let _ = self.effect.Stop();
                    self.output_fault = false;
                }
            }
            return;
        }
        if self.last_magnitude == 0 {
            return; // zero needs no playing effect — let the lease lapse
        }
        let now = Instant::now();
        let due = self.needs_start
            || self
                .effect_started_at
                .is_none_or(|t| now.duration_since(t) >= EFFECT_RETRIGGER_EVERY);
        if !due {
            return;
        }
        // Don't hammer a failing driver at loop rate: a retry after a failed
        // Start waits out the retrigger period. A first Start right after a
        // fresh parameter write is unaffected (last attempt is long past).
        if self
            .last_play_attempt
            .is_some_and(|t| now.duration_since(t) < EFFECT_RETRIGGER_EVERY)
        {
            return;
        }
        unsafe {
            // Refresh the parameters on every PERIODIC retrigger, even though
            // the magnitude is unchanged: the HID transport is asynchronous,
            // so an earlier accepted-but-lost update would otherwise let the
            // base replay a stale stored magnitude forever behind the dedup
            // cache (review-caught). Bounded by one retrigger period. Skipped
            // when the effect isn't playing yet — the 0→nonzero path just
            // wrote these exact parameters.
            if self.effect_started_at.is_some() {
                self.cf.lMagnitude = self.last_magnitude;
                let mut eff = DIEFFECT {
                    dwSize: std::mem::size_of::<DIEFFECT>() as u32,
                    cbTypeSpecificParams: std::mem::size_of::<DICONSTANTFORCE>() as u32,
                    lpvTypeSpecificParams: &mut *self.cf as *mut _ as *mut c_void,
                    ..Default::default()
                };
                if self
                    .effect
                    .SetParameters(&mut eff, DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART)
                    .is_err()
                {
                    self.params_valid = false;
                    self.output_fault = true;
                    return;
                }
            }
            self.last_play_attempt = Some(now);
            match self.effect.Start(1, 0) {
                Ok(()) => {
                    self.effect_started_at = Some(now);
                    self.needs_start = false;
                    self.output_fault = false;
                }
                Err(_) => {
                    self.needs_start = true;
                    self.output_fault = true;
                }
            }
        }
    }
}

impl FfbDevice for DirectInputDevice {
    fn name(&self) -> &str {
        &self.name
    }

    fn rated_torque_nm(&self) -> f32 {
        self.rated_nm
    }

    fn id_hash(&self) -> u32 {
        self.id_hash
    }

    fn connection_epoch(&self) -> u32 {
        self.epoch
    }

    fn poll(&mut self, dt_s: f64) -> WheelState {
        pump_messages(self.hwnd);
        self.ensure_effect_running();
        unsafe {
            let _ = self.device.Poll();
            let mut raw = RawState::default();
            let hr = self.device.GetDeviceState(
                std::mem::size_of::<RawState>() as u32,
                &mut raw as *mut _ as *mut c_void,
            );
            match hr {
                Ok(()) => {
                    let deg = raw.lx as f32 / 32767.0 * (self.range_deg / 2.0);
                    if self.skip_vel_sample {
                        // First sample after (re)acquire: seed, don't differentiate.
                        self.skip_vel_sample = false;
                        self.vel_deg_per_s = 0.0;
                    } else if dt_s > 0.0 {
                        // Light smoothing on the derivative: raw HID deltas are steppy.
                        let v = (deg - self.prev_deg) / dt_s as f32;
                        self.vel_deg_per_s += (v - self.vel_deg_per_s) * 0.3;
                    }
                    self.prev_deg = deg;
                    let mut buttons = 0u32;
                    for (i, b) in raw.buttons.iter().enumerate() {
                        if b & 0x80 != 0 {
                            buttons |= 1 << i;
                        }
                    }
                    WheelState {
                        steering_deg: deg,
                        steering_vel_deg_per_s: self.vel_deg_per_s,
                        buttons,
                        fault: self.output_fault,
                    }
                }
                Err(e) if e.code().0 == DIERR_INPUTLOST || e.code().0 == DIERR_NOTACQUIRED => {
                    if !self.connection_lost {
                        // Entering the lost state disarms immediately, so a
                        // START arriving mid-outage can't become authority
                        // over the wheel when it comes back.
                        self.connection_lost = true;
                        self.epoch = self.epoch.wrapping_add(1);
                    }
                    // Throttle recovery attempts to ~1 Hz — an unplugged
                    // base doesn't need 250 Acquire calls per second
                    // (mirrors evdev's REOPEN_EVERY).
                    let now = Instant::now();
                    let due = self
                        .last_reacquire_attempt
                        .is_none_or(|t| now.duration_since(t) >= REACQUIRE_EVERY);
                    if due {
                        self.last_reacquire_attempt = Some(now);
                        // Re-apply DIPROP_RANGE + DIPROP_AUTOCENTER before
                        // the Acquire (property writes need an unacquired
                        // device): a driver may restore autocenter across a
                        // re-acquire — torque outside every software clamp —
                        // so the properties must be back in force before the
                        // effect can leave quarantine, which the recovery
                        // loop only allows after this Acquire succeeds. If
                        // the gate fails (autocenter refused and not acked),
                        // we do NOT acquire: better no forces than un-gated
                        // ones — retried at 1 Hz.
                        if apply_device_properties(&self.device, self.ack_autocenter).is_ok()
                            && self.device.Acquire().is_ok()
                        {
                            // Acquisition loss unloads effects: quarantine
                            // until a zero rewrite confirms, and bump the
                            // connection epoch — the bridge disarms and
                            // requires a fresh client START before any
                            // torque resumes.
                            self.needs_start = false;
                            self.params_valid = false;
                            self.skip_vel_sample = true;
                            self.last_reacquire_attempt = None;
                            // Recovery is the second transition — bump again
                            // so the session disarms on the RETURN as well.
                            self.connection_lost = false;
                            self.epoch = self.epoch.wrapping_add(1);
                        }
                    }
                    WheelState {
                        steering_deg: self.prev_deg,
                        steering_vel_deg_per_s: 0.0,
                        buttons: 0,
                        fault: true,
                    }
                }
                Err(_) => {
                    WheelState {
                        steering_deg: self.prev_deg,
                        steering_vel_deg_per_s: 0.0,
                        buttons: 0,
                        fault: true,
                    }
                }
            }
        }
    }

    fn set_torque_nm(&mut self, nm: f32) {
        let nm = if nm.is_finite() { nm } else { 0.0 };
        let magnitude = ((nm / self.rated_nm) * DI_FFNOMINALMAX as f32)
            .clamp(-(DI_FFNOMINALMAX as f32), DI_FFNOMINALMAX as f32) as i32;
        if magnitude == self.last_magnitude && self.params_valid {
            return; // known applied — don't spam the driver
        }
        if !self.params_valid && magnitude != 0 {
            // Quarantined: only ZERO may be written — a nonzero write here
            // would re-validate the cache without the confirmed zero the
            // quarantine exists to obtain (review-caught bypass).
            self.output_fault = true;
            return;
        }
        unsafe {
            self.cf.lMagnitude = magnitude;
            let mut eff = DIEFFECT {
                dwSize: std::mem::size_of::<DIEFFECT>() as u32,
                cbTypeSpecificParams: std::mem::size_of::<DICONSTANTFORCE>() as u32,
                lpvTypeSpecificParams: &mut *self.cf as *mut _ as *mut c_void,
                ..Default::default()
            };
            // Type-specific params only, no restart — the proven high-rate path.
            match self
                .effect
                .SetParameters(&mut eff, DIEP_TYPESPECIFICPARAMS | DIEP_NORESTART)
            {
                Ok(()) => {
                    // Cache updates ONLY on success (a failed write may leave
                    // the OLD parameters playing, per the driver contract).
                    self.last_magnitude = magnitude;
                    self.params_valid = true;
                    self.output_fault = false;
                    if magnitude == 0 {
                        // Stop playback too (belt and braces against a lost
                        // zero update in the async HID transport); the play
                        // policy keeps a zero effect unplayed, so a lost
                        // zero decays within one finite lease.
                        let _ = self.effect.Stop();
                        self.effect_started_at = None;
                        self.needs_start = false;
                        // Clear the retry throttle too: the next nonzero
                        // writes fresh parameters and must play immediately,
                        // or a rapid zero crossing would cost up to a
                        // retrigger period of dead torque (review-caught).
                        self.last_play_attempt = None;
                    } else if self.effect_started_at.is_none() {
                        // 0 → nonzero transition: the effect isn't playing.
                        self.needs_start = true;
                        self.ensure_effect_running();
                    }
                }
                Err(_) => {
                    self.params_valid = false;
                    self.output_fault = true;
                    if magnitude == 0 {
                        // A zero MUST take effect by any available means.
                        let stopped = self.effect.Stop().is_ok();
                        let all_stopped =
                            self.device.SendForceFeedbackCommand(DISFFC_STOPALL).is_ok();
                        if stopped || all_stopped {
                            self.last_magnitude = 0;
                            self.params_valid = true;
                            self.last_play_attempt = None;
                            // The device still stores the STALE parameters —
                            // never replay them: the effect stays unplayed at
                            // zero, and any nonzero must rewrite them first.
                            self.needs_start = false;
                            self.effect_started_at = None;
                        }
                        // else: params_valid stays false → quarantined; the
                        // ensure loop retries the forced zero every poll.
                    }
                }
            }
        }
    }
}

impl Drop for DirectInputDevice {
    fn drop(&mut self) {
        unsafe {
            let _ = self.effect.Stop();
            let _ = self.device.SendForceFeedbackCommand(DISFFC_STOPALL);
            let _ = self.device.Unacquire();
        }
    }
}
