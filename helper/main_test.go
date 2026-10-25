package main

import (
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
