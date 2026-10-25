package main

import (
	"bytes"
	"errors"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/kbinani/screenshot"
)

// capture tries the Wayland screenshot tools first (grim for Sway/Hyprland and other wlroots desktops, spectacle for
// KDE, gnome-screenshot for GNOME), then plain X11.
func capture() ([]byte, error) {
	env := os.Environ()
	if os.Getenv("WAYLAND_DISPLAY") == "" {
		// Started before the desktop (for example by systemd at boot): find the Wayland socket ourselves.
		if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
			if m, _ := filepath.Glob(filepath.Join(dir, "wayland-[0-9]*")); len(m) > 0 {
				for _, s := range m {
					if !strings.HasSuffix(s, ".lock") {
						env = append(env, "WAYLAND_DISPLAY="+filepath.Base(s))
						break
					}
				}
			}
		}
	}
	tmp, err := os.CreateTemp("", "issue-mortem-*.png")
	if err != nil {
		return nil, err
	}
	tmp.Close()
	defer os.Remove(tmp.Name())
	var tried []string
	for _, cmd := range [][]string{
		{"grim", tmp.Name()},
		{"spectacle", "-b", "-n", "-f", "-o", tmp.Name()},
		{"gnome-screenshot", "-f", tmp.Name()},
	} {
		if _, err := exec.LookPath(cmd[0]); err != nil {
			continue
		}
		c := exec.Command(cmd[0], cmd[1:]...)
		c.Env = env
		out, err := c.CombinedOutput()
		if b, rerr := os.ReadFile(tmp.Name()); err == nil && rerr == nil && len(b) > 0 {
			return b, nil
		}
		tried = append(tried, cmd[0]+": "+strings.TrimSpace(string(out)))
	}
	if os.Getenv("DISPLAY") == "" {
		if _, err := os.Stat("/tmp/.X11-unix/X0"); err == nil {
			os.Setenv("DISPLAY", ":0") // started at login before the desktop shared DISPLAY
		}
	}
	if os.Getenv("DISPLAY") != "" {
		return x11()
	}
	if len(tried) == 0 {
		return nil, errors.New("no screenshot tool found: install grim (Sway, Hyprland), spectacle (KDE) or gnome-screenshot (GNOME)")
	}
	return nil, errors.New(strings.Join(tried, "; "))
}

func x11() ([]byte, error) {
	n := screenshot.NumActiveDisplays()
	if n == 0 {
		return nil, errors.New("no screens found")
	}
	bounds := screenshot.GetDisplayBounds(0)
	for i := 1; i < n; i++ {
		bounds = bounds.Union(screenshot.GetDisplayBounds(i))
	}
	img, err := screenshot.CaptureRect(bounds)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	err = png.Encode(&buf, img)
	return buf.Bytes(), err
}
