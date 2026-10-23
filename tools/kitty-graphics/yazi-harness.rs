// KetraTerm fixture harness. Upstream serialization bodies are inserted by capture.py.
use base64::{engine::general_purpose, Engine};
use core::str;
use std::fmt::{self, Display};
use std::io::{Read, Write};

type Result<T> = std::io::Result<T>;
const START: &str = "\x1b";
const ESCAPE: &str = "\x1b";
const CLOSE: &str = "";
fn kgp_id() -> u32 {
    73
}

struct KgpPayload(Vec<u8>);
impl KgpPayload {
    fn new(capacity: usize) -> Self {
        Self(Vec::with_capacity(capacity))
    }
}
impl Write for KgpPayload {
    fn write(&mut self, bytes: &[u8]) -> Result<usize> {
        self.0.write(bytes)
    }
    fn flush(&mut self) -> Result<()> {
        Ok(())
    }
}

#[derive(Clone, Copy)]
struct Rect {
    x: u16,
    y: u16,
    width: u16,
    height: u16,
}
impl Rect {
    fn top(self) -> u16 {
        self.y
    }
    fn bottom(self) -> u16 {
        self.y + self.height
    }
    fn left(self) -> u16 {
        self.x
    }
    fn right(self) -> u16 {
        self.x + self.width
    }
}
fn area() -> Rect {
    Rect {
        x: 3,
        y: 4,
        width: 3,
        height: 2,
    }
}

struct Tty;
static TTY: Tty = Tty;
impl Tty {
    fn lockout(&self) -> std::io::Stdout {
        std::io::stdout()
    }
}
struct Adapter;
static ADAPTOR: Adapter = Adapter;
impl Adapter {
    fn shown_area(&self) -> Option<Rect> {
        if std::env::args().nth(1).unwrap() == "delete-image" {
            None
        } else {
            Some(area())
        }
    }
}
macro_rules! writef {
    ($writer:expr, $($args:tt)*) => { write!($writer, $($args)*) };
}

// The fixed name makes the query reproducible. No shared memory is opened.
struct NamedSharedMemory {
    name: String,
}
impl NamedSharedMemory {
    fn new(_: &[u8]) -> Result<Self> {
        Ok(Self {
            name: "/ketraterm-fixture-shm".into(),
        })
    }
}

// UPSTREAM_QUERY
// UPSTREAM_CURSOR
// UPSTREAM_OUTPUT
// UPSTREAM_PLACE

mod deletion {
    use super::*;
    // UPSTREAM_DELETE
}

fn main() -> Result<()> {
    let mode = std::env::args().nth(1).unwrap();
    match mode.as_str() {
        "probes" => print!("{}{}{}", RequestKgp, RequestKgpShm::new(), RequestDA1),
        "rgb" | "rgba" => {
            let mut raw = Vec::new();
            std::io::stdin().read_to_end(&mut raw)?;
            let (format, size) = if mode == "rgb" {
                (24, (73, 19))
            } else {
                (32, (41, 29))
            };
            std::io::stdout().write_all(&output_b64(&raw, format, size)?.0)?;
        }
        "placements" => std::io::stdout().write_all(&place(area(), (73, 19))?)?,
        "delete-placements" | "delete-image" => deletion::image_erase(area())?,
        _ => panic!("unknown capture case"),
    }
    Ok(())
}
