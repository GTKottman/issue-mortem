package main

// The helper's window: a small page served to this computer only, opened as an app window (Chromium-style browsers'
// --app mode, else the default browser). It shows the name, address and key with Copy buttons and opens the notes
// folder. These routes need no key, so they answer only requests from this computer that name it as the host, which
// keeps other computers and web pages out (a web page can't set X-Local without the helper's OK, which it never gives).

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

//go:embed web
var web embed.FS

func local(r *http.Request, port int) bool {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback() &&
		(r.Host == fmt.Sprintf("127.0.0.1:%d", port) || r.Host == fmt.Sprintf("localhost:%d", port))
}

func windowHandler(name, key, folder string, port int, api http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		b, _ := web.ReadFile("web/index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(b)
	})
	mux.HandleFunc("GET /icon.svg", func(w http.ResponseWriter, r *http.Request) {
		b, _ := web.ReadFile("web/icon.svg")
		w.Header().Set("Content-Type", "image/svg+xml")
		w.Write(b)
	})
	mux.HandleFunc("GET /BarlowCondensed-SemiBold.ttf", func(w http.ResponseWriter, r *http.Request) {
		b, _ := web.ReadFile("web/BarlowCondensed-SemiBold.ttf") // SIL Open Font License, web/OFL.txt
		w.Header().Set("Content-Type", "font/ttf")
		w.Write(b)
	})
	mux.HandleFunc("GET /local/info", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Local") != "1" {
			http.Error(w, "no", http.StatusForbidden)
			return
		}
		tailscale, other := addresses()
		for i := range tailscale {
			tailscale[i] = fmt.Sprintf("%s:%d", tailscale[i], port)
		}
		for i := range other {
			other[i] = fmt.Sprintf("%s:%d", other[i], port)
		}
		rc, status := relayStatus()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"name": name, "tailscale": tailscale, "other": other,
			"key": key, "folder": folder, "version": version,
			"relay": map[string]string{"url": rc.URL, "status": status, "address": relayAddress(key)}})
	})
	mux.HandleFunc("POST /local/relay", func(w http.ResponseWriter, r *http.Request) {
		var c relayConfig
		if r.Header.Get("X-Local") != "1" || json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&c) != nil {
			http.Error(w, "no", http.StatusForbidden)
			return
		}
		if err := setRelay(c, api, key, true); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
	})
	mux.HandleFunc("POST /local/open-folder", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Local") != "1" {
			http.Error(w, "no", http.StatusForbidden)
			return
		}
		os.MkdirAll(folder, 0o755)
		if err := openPath(folder); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
	})
	return mux
}

// openWindow shows url in an app window: Chrome, Chromium, Edge or Brave in --app mode, else the default browser.
func openWindow(url string) error {
	app := []string{"--app=" + url, "--window-size=560,640"}
	var tries [][]string
	switch runtime.GOOS {
	case "windows":
		for _, p := range []string{os.Getenv("ProgramFiles(x86)"), os.Getenv("ProgramFiles"), os.Getenv("LocalAppData")} {
			for _, b := range []string{`Microsoft\Edge\Application\msedge.exe`, `Google\Chrome\Application\chrome.exe`} {
				if p != "" {
					tries = append(tries, append([]string{filepath.Join(p, b)}, app...))
				}
			}
		}
		tries = append(tries, []string{"rundll32", "url.dll,FileProtocolHandler", url})
	case "darwin":
		for _, b := range []string{"Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium"} {
			if _, err := os.Stat("/Applications/" + b + ".app"); err == nil {
				tries = append(tries, append([]string{"open", "-na", b, "--args"}, app...))
			}
		}
		tries = append(tries, []string{"open", url})
	default:
		for _, b := range []string{"chromium", "google-chrome", "google-chrome-stable", "brave", "brave-browser", "microsoft-edge"} {
			tries = append(tries, append([]string{b}, app...))
		}
		tries = append(tries, []string{"xdg-open", url})
	}
	for _, t := range tries {
		if strings.ContainsRune(t[0], filepath.Separator) {
			if _, err := os.Stat(t[0]); err != nil {
				continue
			}
		} else if _, err := exec.LookPath(t[0]); err != nil {
			continue
		}
		c := exec.Command(t[0], t[1:]...)
		detach(c)
		if c.Start() == nil {
			go c.Wait()
			return nil
		}
	}
	return fmt.Errorf("no browser found")
}

func openPath(p string) error {
	var c *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		c = exec.Command("explorer", p)
	case "darwin":
		c = exec.Command("open", p)
	default:
		c = exec.Command("xdg-open", p)
	}
	detach(c)
	if err := c.Start(); err != nil {
		return err
	}
	go c.Wait()
	return nil
}
