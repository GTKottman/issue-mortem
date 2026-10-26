package main

// Away from home through your own server: when a relay is set (in the window, saved to relay.json next to the key),
// the helper keeps polling it for app calls and answers them with its own API, key check included. The relay program
// is in relay/ at the top of the repo. The app reaches this computer at <relay>/c/<id>.

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type relayConfig struct {
	URL    string `json:"url"`
	Secret string `json:"secret"`
}

type relayState struct {
	mu     sync.Mutex
	config relayConfig
	gen    int    // bumped on every change, so old pollers stop
	status string // shown in the window
}

var relayNow relayState

func relayFile() string {
	dir, _ := os.UserConfigDir()
	return filepath.Join(dir, "issue-mortem", "relay.json")
}

// relayID is this computer's name on the relay. It comes from the key, so a new key also means a new address.
func relayID(key string) string {
	sum := sha256.Sum256([]byte("issue-mortem-relay:" + key))
	return hex.EncodeToString(sum[:12])
}

// relayAddress is what the app types in as the address; "" when no relay is set.
func relayAddress(key string) string {
	relayNow.mu.Lock()
	defer relayNow.mu.Unlock()
	if relayNow.config.URL == "" {
		return ""
	}
	return relayNow.config.URL + "/c/" + relayID(key)
}

func relayStatus() (relayConfig, string) {
	relayNow.mu.Lock()
	defer relayNow.mu.Unlock()
	return relayNow.config, relayNow.status
}

func setRelayStatus(gen int, s string) {
	relayNow.mu.Lock()
	if gen == relayNow.gen {
		relayNow.status = s
	}
	relayNow.mu.Unlock()
}

// cleanRelayURL accepts "relay.example.com" or a full URL and gives "https://relay.example.com".
func cleanRelayURL(u string) (string, error) {
	u = strings.TrimRight(strings.TrimSpace(u), "/")
	if u == "" {
		return "", nil
	}
	if !strings.Contains(u, "://") {
		u = "https://" + u
	}
	if !strings.HasPrefix(u, "https://") && !strings.HasPrefix(u, "http://") {
		return "", fmt.Errorf("the server address should start with https://")
	}
	return u, nil
}

// setRelay saves the relay (an empty URL turns it off) and restarts the pollers.
func setRelay(c relayConfig, api http.Handler, key string, save bool) error {
	u, err := cleanRelayURL(c.URL)
	if err != nil {
		return err
	}
	c.URL, c.Secret = u, strings.TrimSpace(c.Secret)
	if c.URL != "" && c.Secret == "" {
		return fmt.Errorf("type the server's relay password too")
	}
	if save {
		b, _ := json.MarshalIndent(c, "", "  ")
		if err := os.WriteFile(relayFile(), b, 0o600); err != nil {
			return err
		}
	}
	relayNow.mu.Lock()
	relayNow.gen++
	gen := relayNow.gen
	relayNow.config = c
	relayNow.status = ""
	if c.URL != "" {
		relayNow.status = "Connecting…"
	}
	relayNow.mu.Unlock()
	if c.URL != "" {
		go relayHello(gen, c)
		for i := 0; i < 2; i++ { // two at once, so a slow screenshot doesn't hold up the next call
			go relayPoller(gen, c, api, key)
		}
	}
	return nil
}

// startRelay loads relay.json at startup.
func startRelay(api http.Handler, key string) {
	b, err := os.ReadFile(relayFile())
	if err != nil {
		return
	}
	var c relayConfig
	if json.Unmarshal(b, &c) == nil && c.URL != "" {
		if err := setRelay(c, api, key, false); err != nil {
			log.Printf("relay: %v", err)
		}
	}
}

func relayCurrent(gen int) bool {
	relayNow.mu.Lock()
	defer relayNow.mu.Unlock()
	return gen == relayNow.gen
}

// relayHello checks the address and password straight away, so the window doesn't wait for the first poll.
func relayHello(gen int, c relayConfig) {
	req, _ := http.NewRequest("GET", c.URL+"/helper/hello", nil)
	req.Header.Set("X-Relay-Secret", c.Secret)
	res, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	switch {
	case err != nil:
		setRelayStatus(gen, "Can't reach the server: "+err.Error())
	case res.StatusCode == http.StatusOK:
		setRelayStatus(gen, "Connected")
	case res.StatusCode == http.StatusUnauthorized:
		setRelayStatus(gen, "Wrong relay password")
	default:
		setRelayStatus(gen, "The server said "+res.Status+". Is it an Issue Mortem relay?")
	}
	if res != nil {
		res.Body.Close()
	}
}

func relayPoller(gen int, c relayConfig, api http.Handler, key string) {
	client := &http.Client{Timeout: 60 * time.Second}
	id := relayID(key)
	for relayCurrent(gen) {
		req, _ := http.NewRequest("GET", c.URL+"/helper/poll", nil)
		req.Header.Set("X-Relay-Secret", c.Secret)
		req.Header.Set("X-Helper-Id", id)
		res, err := client.Do(req)
		if err != nil {
			setRelayStatus(gen, "Can't reach the server: "+err.Error())
			time.Sleep(5 * time.Second)
			continue
		}
		switch res.StatusCode {
		case http.StatusNoContent:
			res.Body.Close()
			setRelayStatus(gen, "Connected")
		case http.StatusOK:
			setRelayStatus(gen, "Connected")
			relayAnswer(client, c, api, res)
		case http.StatusUnauthorized:
			res.Body.Close()
			setRelayStatus(gen, "Wrong relay password")
			time.Sleep(30 * time.Second)
		default:
			res.Body.Close()
			setRelayStatus(gen, "The server said "+res.Status+". Is it an Issue Mortem relay?")
			time.Sleep(10 * time.Second)
		}
	}
}

// relayAnswer runs one call from the relay through the helper's API and posts the answer back.
func relayAnswer(client *http.Client, c relayConfig, api http.Handler, res *http.Response) {
	body, err := io.ReadAll(io.LimitReader(res.Body, 110<<20))
	res.Body.Close()
	if err != nil {
		return
	}
	path := res.Header.Get("X-Path")
	if !strings.HasPrefix(path, "/api/") {
		return
	}
	in := httptest.NewRequest(res.Header.Get("X-Method"), path, bytes.NewReader(body))
	in.Header.Set("X-Key", res.Header.Get("X-Key"))
	in.Header.Set("Content-Type", res.Header.Get("Content-Type"))
	out := httptest.NewRecorder()
	api.ServeHTTP(out, in)

	reply, _ := http.NewRequest("POST", c.URL+"/helper/reply/"+res.Header.Get("X-Call-Id"), bytes.NewReader(out.Body.Bytes()))
	reply.Header.Set("X-Relay-Secret", c.Secret)
	reply.Header.Set("X-Status", strconv.Itoa(out.Code))
	reply.Header.Set("Content-Type", out.Header().Get("Content-Type"))
	if r, err := client.Do(reply); err == nil {
		r.Body.Close()
	}
}
