package main

import (
	"errors"
	"os"
	"os/exec"
	"strings"
)

// capture uses macOS's own screencapture tool (main display). The first time, macOS asks to allow Screen Recording
// for the app that started the helper (Terminal, for example).
func capture() ([]byte, error) {
	tmp, err := os.CreateTemp("", "issue-mortem-*.png")
	if err != nil {
		return nil, err
	}
	tmp.Close()
	defer os.Remove(tmp.Name())
	out, err := exec.Command("screencapture", "-x", "-t", "png", tmp.Name()).CombinedOutput()
	if err != nil {
		return nil, errors.New(strings.TrimSpace(string(out)) + " " + err.Error())
	}
	b, err := os.ReadFile(tmp.Name())
	if err == nil && len(b) == 0 {
		err = errors.New("screencapture made an empty file: allow Screen Recording in System Settings > Privacy & Security")
	}
	return b, err
}
