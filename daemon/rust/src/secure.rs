// secure.rs — compile-time XOR obfuscation for sensitive string literals.
// Mirrors the original C string_enc.h: const-time encode, runtime decrypt.
// NOTE: this is anti-RE hardening (slows static analysis), NOT real security —
// the key ships inside the binary and is recoverable with any debugger.

pub const KEY: u8 = 0x5A;
const BUF_LEN: usize = 256;

/// const fn XOR-encode a string literal into a fixed 256-byte buffer.
/// Decryptable with `dec()`. Null-terminated (decoded) like the C version.
pub const fn enc_arr(s: &[u8]) -> [u8; BUF_LEN] {
    let mut out = [0u8; BUF_LEN];
    let mut i = 0;
    while i < s.len() && i < BUF_LEN - 1 {
        out[i] = s[i] ^ (KEY ^ i as u8);
        i += 1;
    }
    out
}

/// Runtime decrypt of an enc_arr() buffer. Stops at decoded NUL.
pub fn dec(enc: &[u8; BUF_LEN]) -> String {
    let mut out = String::with_capacity(BUF_LEN);
    for (i, b) in enc.iter().enumerate() {
        if *b == 0 { break; }
        out.push((b ^ (KEY ^ i as u8)) as char);
    }
    out
}

/// Macro: decrypt-once-per-process into a static, returns &'static str.
/// Usage: `zen_path!("/sys/class/thermal")`
#[macro_export]
macro_rules! zen_path {
    ($s:expr) => {{
        static ONCE: std::sync::OnceLock<String> = std::sync::OnceLock::new();
        ONCE.get_or_init(|| $crate::secure::dec(&$crate::secure::enc_arr($s.as_bytes())))
            .as_str()
    }};
}