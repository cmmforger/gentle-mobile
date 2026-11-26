//! Bindgen entry point: `cargo run -p gentle-mobile-core --bin uniffi-bindgen -- generate …`
//!
//! The binary comes from the `uniffi` crate's `cli` feature so the binding
//! generator version always matches the scaffolding version of this crate.

fn main() {
    uniffi::uniffi_bindgen_main()
}
