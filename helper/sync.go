package main

// Sync: the app's Sync button makes the tablet listen on syncPort for a minute. Meanwhile every helper keeps asking
// Tailscale which of the same person's Android devices are online and offers itself (name, address, key) to each one.
// Tablets that aren't syncing simply refuse the connection. Only devices signed in to the same Tailscale account get
// the key. Without Tailscale, lan.go does the same on the home network.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"
)

const syncPort = 8642

// tailscaleStatus is the part of `tailscale status --json` sync needs.
type tailscaleStatus struct {
	Self struct {
		TailscaleIPs []string
		UserID       int64
	}
	Peer map[string]struct {
		HostName     string
		OS           string
		Online       bool
		UserID       int64
		TailscaleIPs []string
	}
}

// tablets returns the IPv4 Tailscale addresses of the same person's online Android devices, and this computer's own.
func tablets(st tailscaleStatus) (peers []string, self string) {
	self = firstIPv4(st.Self.TailscaleIPs)
	for _, p := range st.Peer {
		if p.Online && p.OS == "android" && p.UserID == st.Self.UserID {
			if ip := firstIPv4(p.TailscaleIPs); ip != "" {
				peers = append(peers, ip)
			}
		}
	}
	return peers, self
}

func firstIPv4(ips []string) string {
	for _, ip := range ips {
		if !bytes.ContainsRune([]byte(ip), ':') {
			return ip
		}
	}
	return ""
}

// offerLoop runs for as long as the helper serves.
func offerLoop(name, key string, port int) {
	sent := map[string]time.Time{} // a tablet that got us is left alone until its sync minute is over
	for ; ; time.Sleep(5 * time.Second) {
		st, err := status()
		if err != nil {
			continue
		}
		peers, self := tablets(st)
		if self == "" {
			continue
		}
		for _, ip := range peers {
			if time.Since(sent[ip]) < 70*time.Second {
				continue
			}
			if err := offer(fmt.Sprintf("http://%s:%d/sync", ip, syncPort), name, fmt.Sprintf("%s:%d", self, port), key); err == nil {
				sent[ip] = time.Now()
				log.Printf("synced with %s", ip)
			}
		}
	}
}

func offer(url, name, address, key string) error {
	body, _ := json.Marshal(map[string]string{"name": name, "address": address, "key": key})
	res, err := (&http.Client{Timeout: 3 * time.Second}).Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("tablet said %s", res.Status)
	}
	return nil
}

func status() (tailscaleStatus, error) {
	var st tailscaleStatus
	c := exec.Command(tailscaleCLI(), "status", "--json")
	hide(c)
	out, err := c.Output()
	if err != nil {
		return st, err
	}
	return st, json.Unmarshal(out, &st)
}

// tailscaleCLI finds the tailscale command: on the PATH, or where the Windows and Mac apps put it.
func tailscaleCLI() string {
	if p, err := exec.LookPath("tailscale"); err == nil {
		return p
	}
	for _, p := range map[string][]string{
		"windows": {`C:\Program Files\Tailscale\tailscale.exe`},
		"darwin":  {"/Applications/Tailscale.app/Contents/MacOS/Tailscale"},
	}[runtime.GOOS] {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return "tailscale"
}
