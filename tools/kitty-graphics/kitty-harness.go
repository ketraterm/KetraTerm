// KetraTerm fixture harness. Upstream serialization bodies are inserted by capture.py.
package main

import (
	"bytes"
	"compress/zlib"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"strings"
)

// UPSTREAM_WRITER
// UPSTREAM_ENUM_STRINGS

func main() {
	payload, err := io.ReadAll(os.Stdin)
	if err != nil {
		panic(err)
	}
	command := GraphicsCommand{a: GRT_action_transmit_and_display, q: GRT_quiet_silent}
	switch os.Args[1] {
	case "png":
		command.f, command.s, command.v, command.i = GRT_format_png, 2, 2, 101
	case "zlib-rgb":
		command.f, command.s, command.v, command.i = GRT_format_rgb, 128, 64, 102
	case "large-rgba":
		command.f, command.s, command.v, command.i = GRT_format_rgba, 199, 181, 103
	default:
		panic("unknown capture case")
	}
	if err := command.WriteWithPayloadTo(os.Stdout, payload); err != nil {
		panic(err)
	}
}
