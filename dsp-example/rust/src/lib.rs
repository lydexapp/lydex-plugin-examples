//! Minimal Lydex DSP plug-in example — Lydex DSP ABI v1.
//!
//! A tiny but real effect you can build, install, and adapt: an output trim
//! plus gentle even-harmonic "warmth" (an asymmetric tanh). Two parameters:
//!
//!   id 0  Warmth  0..100        drive of the asymmetric tanh
//!   id 1  Trim    0..100        output gain, 50 = unity
//!
//! ## The ABI
//!
//! Audio is exchanged through `SCRATCH`, a buffer in this module's own linear
//! memory. `lydex_dsp_prepare` returns its address; the host writes
//! `frames * channels` **interleaved f64** samples there, calls
//! `lydex_dsp_process`, then reads them back. Everything is in place and
//! real-time — no allocation, no blocking, no panics.

#![no_std]

use core::cell::UnsafeCell;
use core::sync::atomic::{AtomicI32, Ordering};

// Largest block the host will hand us, in samples (frames * channels).
const MAX_FRAMES: usize = 8192;
const MAX_CHANNELS: usize = 2;
const SCRATCH_SAMPLES: usize = MAX_FRAMES * MAX_CHANNELS;

struct Scratch(UnsafeCell<[f64; SCRATCH_SAMPLES]>);
// Single-threaded audio callback: the host never calls process() re-entrantly.
unsafe impl Sync for Scratch {}
static SCRATCH: Scratch = Scratch(UnsafeCell::new([0.0; SCRATCH_SAMPLES]));

static ENABLED: AtomicI32 = AtomicI32::new(1);
// Parameters as milli-units (value * 1000) so we stay lock-free without floats.
static WARMTH: AtomicI32 = AtomicI32::new(40_000); // manifest default 40
static TRIM: AtomicI32 = AtomicI32::new(50_000); // 50 = unity gain

/// Build state for the sample rate and return the audio buffer address.
#[no_mangle]
pub extern "C" fn lydex_dsp_prepare(_rate: i32, _channels: i32, _max_frames: i32) -> i32 {
    SCRATCH.0.get() as usize as i32
}

/// Process one block in place.
#[no_mangle]
pub extern "C" fn lydex_dsp_process(frames: i32, channels: i32) {
    if frames <= 0 || channels <= 0 {
        return;
    }
    if ENABLED.load(Ordering::Relaxed) == 0 {
        return;
    }
    let total = (frames as usize) * (channels as usize);
    if total > SCRATCH_SAMPLES {
        return;
    }

    let warmth = WARMTH.load(Ordering::Relaxed) as f64 / 100_000.0; // 0..1
    let trim = TRIM.load(Ordering::Relaxed) as f64 / 50_000.0; // 0..2, 1 = unity

    // A DC bias before tanh, removed after, generates even harmonics (2nd,
    // 4th) — the "round / sweet" tone. drive tracks Warmth.
    let drive = 1.0 + warmth * 3.0;
    let bias = warmth * 0.15;
    let bias_out = libm::tanh(bias);

    let buf = unsafe { core::slice::from_raw_parts_mut(SCRATCH.0.get() as *mut f64, total) };
    for x in buf.iter_mut() {
        let y = libm::tanh(*x * drive + bias) - bias_out;
        *x = (y / drive) * trim;
    }
}

/// Set parameter `id` to `value`, in the 0..100 range declared in the manifest.
#[no_mangle]
pub extern "C" fn lydex_dsp_set_param(id: i32, value: f64) {
    let clamped = if value < 0.0 {
        0.0
    } else if value > 100.0 {
        100.0
    } else {
        value
    };
    let v = (clamped * 1000.0) as i32;
    match id {
        0 => WARMTH.store(v, Ordering::Relaxed),
        1 => TRIM.store(v, Ordering::Relaxed),
        _ => {}
    }
}

#[no_mangle]
pub extern "C" fn lydex_dsp_reset() {}

#[no_mangle]
pub extern "C" fn lydex_dsp_latency_frames() -> i32 {
    0
}

#[no_mangle]
pub extern "C" fn lydex_dsp_enabled() -> i32 {
    ENABLED.load(Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn lydex_dsp_set_enabled(on: i32) {
    ENABLED.store(on, Ordering::Relaxed);
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    loop {}
}
