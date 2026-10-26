package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestKeyAndFiles(t *testing.T) {
	dir := t.TempDir()
	h := handler("abc", "test-pc", dir)
	do := func(method, url, key, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, url, strings.NewReader(body))
		if key != "" {
			r.Header.Set("X-Key", key)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := do("GET", "/api/hello", "nope", ""); w.Code != http.StatusUnauthorized {
		t.Fatalf("wrong key: got %d", w.Code)
	}
	if w := do("GET", "/api/hello", "abc", ""); w.Code != 200 || !strings.Contains(w.Body.String(), "test-pc") {
		t.Fatalf("hello: %d %s", w.Code, w.Body)
	}
	do("PUT", "/api/file?path=/AI%20Inbox/a.md", "abc", "one")
	do("PUT", "/api/file?path=/AI%20Inbox/a.md", "abc", "two")
	for name, want := range map[string]string{"a.md": "one", "a (2).md": "two"} {
		b, err := os.ReadFile(filepath.Join(dir, "AI Inbox", name))
		if err != nil || string(b) != want {
			t.Fatalf("%s: %q %v", name, b, err)
		}
	}
	if w := do("PUT", "/api/file?path=/../escape.md", "abc", "x"); w.Code != http.StatusBadRequest {
		t.Fatalf("escape allowed: %d", w.Code)
	}
}

func TestSync(t *testing.T) {
	var st tailscaleStatus
	err := json.Unmarshal([]byte(`{"Self":{"TailscaleIPs":["100.1.1.1","fd7a::1"],"UserID":7},"Peer":{
		"a":{"HostName":"tab","OS":"android","Online":true,"UserID":7,"TailscaleIPs":["100.2.2.2"]},
		"b":{"HostName":"off","OS":"android","Online":false,"UserID":7,"TailscaleIPs":["100.3.3.3"]},
		"c":{"HostName":"friend","OS":"android","Online":true,"UserID":8,"TailscaleIPs":["100.4.4.4"]},
		"d":{"HostName":"pc","OS":"windows","Online":true,"UserID":7,"TailscaleIPs":["100.5.5.5"]}}}`), &st)
	if err != nil {
		t.Fatal(err)
	}
	peers, self := tablets(st)
	if self != "100.1.1.1" || len(peers) != 1 || peers[0] != "100.2.2.2" {
		t.Fatalf("tablets: %v %q", peers, self)
	}
	var got map[string]string
	tab := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&got)
	}))
	defer tab.Close()
	if err := offer(tab.URL+"/sync", "pc", "100.1.1.1:8642", "k"); err != nil || got["key"] != "k" || got["address"] != "100.1.1.1:8642" {
		t.Fatalf("offer: %v %v", err, got)
	}
}

func TestWindowOnlyHere(t *testing.T) {
	r := httptest.NewRequest("GET", "http://127.0.0.1:8642/", nil)
	r.RemoteAddr = "127.0.0.1:5000"
	if !local(r, 8642) {
		t.Fatal("this computer should see the window")
	}
	r.RemoteAddr = "100.2.2.2:5000"
	if local(r, 8642) {
		t.Fatal("another computer must not")
	}
	r = httptest.NewRequest("GET", "http://evil.example:8642/", nil) // DNS rebinding: right address, wrong host
	r.RemoteAddr = "127.0.0.1:5000"
	if local(r, 8642) {
		t.Fatal("a web page under another name must not")
	}
	w := httptest.NewRecorder()
	windowHandler("pc", "k", t.TempDir(), 8642).ServeHTTP(w, httptest.NewRequest("GET", "/local/info", nil))
	if w.Code != http.StatusForbidden {
		t.Fatalf("info without X-Local: %d", w.Code)
	}
}
