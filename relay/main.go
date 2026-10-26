// Issue Mortem relay: lets the app reach a computer away from home through a server you run, without Tailscale and
// without opening anything on the home router. The helper on each computer dials out to the relay and waits for work
// (long polling); the app calls the relay over HTTPS and the relay hands each call to that computer's helper.
//
//	GET  /helper/hello           helper: check the password (200 straight away)
//	GET  /helper/poll            helper: wait up to 25 s for a call (204 when there's none)
//	POST /helper/reply/{call}    helper: the answer to a call
//	ANY  /c/{id}/api/...         app: a helper API call, passed on to helper {id} as it is
//
// Helpers need the relay password (RELAY_SECRET) to connect, so only your own computers can use the relay. The app's
// calls still need the computer's key: the relay passes them on and the helper checks the key. Put the relay behind
// HTTPS (Caddy, Traefik, Coolify...): the password, keys and screenshots pass through it.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync"
	"time"
)

const (
	pollWait  = 25 * time.Second
	callWait  = 90 * time.Second
	offline   = 40 * time.Second // a helper that hasn't polled for this long is offline
	maxBody   = 110 << 20
	queueSize = 16
)

var validID = regexp.MustCompile(`^[0-9a-f]{8,64}$`)

type call struct {
	id                             string // set once a helper takes it
	method, path, key, contentType string
	body                           []byte
	answer                         chan answer
}

type answer struct {
	status      int
	contentType string
	body        []byte
}

type helper struct {
	queue    chan *call
	lastSeen time.Time
	polling  int
}

type relay struct {
	secret  string
	mu      sync.Mutex
	helpers map[string]*helper
	pending map[string]*call
}

func main() {
	secret := os.Getenv("RELAY_SECRET")
	if len(secret) < 16 {
		log.Fatal("set RELAY_SECRET to a password of at least 16 characters; helpers need it to connect")
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	r := &relay{secret: secret, helpers: map[string]*helper{}, pending: map[string]*call{}}
	log.Printf("Issue Mortem relay listening on port %s", port)
	log.Fatal(http.ListenAndServe(":"+port, r.routes()))
}

func (r *relay) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte("Issue Mortem relay is running.\n"))
	})
	mux.HandleFunc("GET /helper/hello", func(w http.ResponseWriter, req *http.Request) {
		if r.helperAuth(w, req) {
			w.Write([]byte("ok\n"))
		}
	})
	mux.HandleFunc("GET /helper/poll", r.poll)
	mux.HandleFunc("POST /helper/reply/{call}", r.reply)
	mux.HandleFunc("/c/{id}/api/{rest...}", r.forward)
	return mux
}

func (r *relay) helperAuth(w http.ResponseWriter, req *http.Request) bool {
	if subtle.ConstantTimeCompare([]byte(req.Header.Get("X-Relay-Secret")), []byte(r.secret)) != 1 {
		time.Sleep(time.Second) // makes guessing the password slow
		http.Error(w, "wrong relay password", http.StatusUnauthorized)
		return false
	}
	return true
}

func (r *relay) helper(id string) *helper {
	h := r.helpers[id]
	if h == nil {
		h = &helper{queue: make(chan *call, queueSize)}
		r.helpers[id] = h
	}
	return h
}

func (r *relay) poll(w http.ResponseWriter, req *http.Request) {
	if !r.helperAuth(w, req) {
		return
	}
	id := req.Header.Get("X-Helper-Id")
	if !validID.MatchString(id) {
		http.Error(w, "bad helper id", http.StatusBadRequest)
		return
	}
	r.mu.Lock()
	h := r.helper(id)
	h.lastSeen = time.Now()
	h.polling++
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		h.polling--
		h.lastSeen = time.Now()
		r.mu.Unlock()
	}()

	var c *call
	select {
	case c = <-h.queue:
	case <-time.After(pollWait):
		w.WriteHeader(http.StatusNoContent)
		return
	case <-req.Context().Done():
		return
	}
	callID := newID()
	r.mu.Lock()
	c.id = callID
	r.pending[callID] = c
	r.mu.Unlock()
	w.Header().Set("X-Call-Id", callID)
	w.Header().Set("X-Method", c.method)
	w.Header().Set("X-Path", c.path)
	w.Header().Set("X-Key", c.key)
	w.Header().Set("Content-Type", c.contentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(c.body)))
	if _, err := w.Write(c.body); err != nil {
		r.finish(callID, answer{status: http.StatusBadGateway, body: []byte("the computer dropped the call")})
	}
}

// finish hands an answer to the waiting app call, once.
func (r *relay) finish(callID string, a answer) bool {
	r.mu.Lock()
	c := r.pending[callID]
	delete(r.pending, callID)
	r.mu.Unlock()
	if c == nil {
		return false
	}
	c.answer <- a
	return true
}

func (r *relay) reply(w http.ResponseWriter, req *http.Request) {
	if !r.helperAuth(w, req) {
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, req.Body, maxBody))
	if err != nil {
		http.Error(w, "answer too big", http.StatusRequestEntityTooLarge)
		return
	}
	status, _ := strconv.Atoi(req.Header.Get("X-Status"))
	if status < 100 || status > 599 {
		status = http.StatusBadGateway
	}
	if !r.finish(req.PathValue("call"), answer{status: status, contentType: req.Header.Get("Content-Type"), body: body}) {
		http.Error(w, "no such call (it timed out)", http.StatusGone)
	}
}

func (r *relay) forward(w http.ResponseWriter, req *http.Request) {
	id := req.PathValue("id")
	if !validID.MatchString(id) {
		http.Error(w, "no such computer", http.StatusNotFound)
		return
	}
	r.mu.Lock()
	h := r.helpers[id]
	online := h != nil && (h.polling > 0 || time.Since(h.lastSeen) < offline)
	r.mu.Unlock()
	if !online {
		http.Error(w, "that computer isn't connected to this relay (is its helper running?)", http.StatusBadGateway)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, req.Body, maxBody))
	if err != nil {
		http.Error(w, "too big", http.StatusRequestEntityTooLarge)
		return
	}
	path := "/api/" + req.PathValue("rest")
	if req.URL.RawQuery != "" {
		path += "?" + req.URL.RawQuery
	}
	c := &call{method: req.Method, path: path, key: req.Header.Get("X-Key"), contentType: req.Header.Get("Content-Type"),
		body: body, answer: make(chan answer, 1)}
	select {
	case h.queue <- c:
	default:
		http.Error(w, "that computer is busy, try again", http.StatusServiceUnavailable)
		return
	}
	select {
	case a := <-c.answer:
		if a.contentType != "" {
			w.Header().Set("Content-Type", a.contentType)
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(a.body)))
		w.WriteHeader(a.status)
		io.Copy(w, bytes.NewReader(a.body))
	case <-time.After(callWait):
		http.Error(w, "the computer didn't answer in time", http.StatusGatewayTimeout)
	case <-req.Context().Done():
	}
	r.mu.Lock()
	delete(r.pending, c.id)
	r.mu.Unlock()
}

func newID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}
