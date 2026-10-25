package main

import (
	"os"
	"testing"
)

// SHOT=out.png go test -run TestCapture: takes a real screenshot of this computer.
func TestCapture(t *testing.T) {
	out := os.Getenv("SHOT")
	if out == "" {
		t.Skip("set SHOT=file.png to take a real screenshot")
	}
	b, err := capture()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(out, b, 0o644); err != nil {
		t.Fatal(err)
	}
}
