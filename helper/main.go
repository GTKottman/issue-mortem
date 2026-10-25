// Issue Mortem helper: runs on a computer so the Issue Mortem app can take a screenshot of it and drop notes into a
// folder on it. Every request needs the key this program prints when it starts.
//
//	GET /api/hello            {"name": "<computer name>", "version": "..."}, for the app's Test button
//	GET /api/screen           the whole screen right now, as a PNG
//	PUT /api/file?path=/x/y   saves the request body under the notes folder (never overwrites: adds " (2)" etc.)
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const version = "0.0.1"

// With no command (or "serve") it serves: that's what the installers start at login. "info" starts it if it isn't
// running and shows the name, address and key in a window (the Start menu, Applications and app menu entries).
func main() {
	home, _ := os.UserHomeDir()
	port := flag.Int("port", 8642, "port to listen on")
	folder := flag.String("folder", filepath.Join(home, "Issue Mortem"), "where notes sent from the app are saved")
	newKey := flag.Bool("new-key", false, "make a new key (the app needs the new one)")
	args := os.Args[1:]
	command := ""
	if len(args) > 0 && (args[0] == "info" || args[0] == "serve") {
		command, args = args[0], args[1:]
	}
	if command == "" && openedAsMacApp() {
		command = "info" // double-clicked "Issue Mortem Helper.app"; its login item runs "serve"
	}
	info := command == "info"
	flag.CommandLine.Parse(args)

	key, err := loadKey(*newKey)
	if err != nil {
		log.Fatalf("can't read or make the key: %v", err)
	}
	name, _ := os.Hostname()
	text := infoText(name, key, *port, *folder)

	if info {
		if !running(*port, key) {
			if err := startBackground(args); err != nil {
				showInfo("Issue Mortem Helper couldn't start: " + err.Error())
				return
			}
			for i := 0; i < 30 && !running(*port, key); i++ {
				time.Sleep(100 * time.Millisecond)
			}
		}
		showInfo(text)
		return
	}

	logToFile()
	fmt.Println(text)
	fmt.Println("Leave this running. Ctrl+C stops it.")
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", *port))
	if err != nil {
		if running(*port, key) {
			log.Printf("already running on port %d", *port)
			return
		}
		log.Fatalf("can't listen on port %d: %v", *port, err)
	}
	log.Printf("listening on port %d", *port)
	log.Fatal(http.Serve(ln, handler(key, name, *folder)))
}

func infoText(name, key string, port int, folder string) string {
	var b strings.Builder
	b.WriteString("Issue Mortem Helper " + version + "\n\n")
	b.WriteString("In the app, open Settings > Add computer and type:\n\n")
	b.WriteString("Name:  " + name + "\n")
	tailscale, other := addresses()
	for _, a := range tailscale {
		fmt.Fprintf(&b, "Address:  %s:%d\n", a, port)
	}
	if len(tailscale) == 0 {
		b.WriteString("Address:  no Tailscale address found. Is Tailscale running on this computer?\n")
		for _, a := range other {
			fmt.Fprintf(&b, "    (on the same Wi-Fi only: %s:%d)\n", a, port)
		}
	}
	b.WriteString("Key:  " + key + "\n\n")
	b.WriteString("Notes you Send or Save from the app go to " + folder)
	return b.String()
}

func openedAsMacApp() bool {
	self, _ := os.Executable()
	return runtime.GOOS == "darwin" && strings.Contains(self, ".app/Contents/MacOS/")
}

// running asks the port whether our helper (same key) already answers there.
func running(port int, key string) bool {
	req, _ := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/api/hello", port), nil)
	req.Header.Set("X-Key", key)
	res, err := (&http.Client{Timeout: time.Second}).Do(req)
	if err != nil {
		return false
	}
	res.Body.Close()
	return res.StatusCode == http.StatusOK
}

// startBackground runs this program again, without "info", detached from this one.
func startBackground(args []string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	c := exec.Command(self, append([]string{"serve"}, args...)...)
	detach(c)
	return c.Start()
}

// logToFile also writes the log to issue-mortem/helper.log in the user's cache folder, since an installed helper has
// no window to print to.
func logToFile() {
	dir, err := os.UserCacheDir()
	if err != nil {
		return
	}
	dir = filepath.Join(dir, "issue-mortem")
	os.MkdirAll(dir, 0o700)
	f, err := os.OpenFile(filepath.Join(dir, "helper.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(f, os.Stderr)) // file first: with no console, writing to stderr fails
}

func handler(key, name, folder string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/hello", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"name": name, "version": version})
	})
	mux.HandleFunc("GET /api/screen", func(w http.ResponseWriter, r *http.Request) {
		png, err := capture()
		if err != nil {
			log.Printf("screenshot failed: %v", err)
			http.Error(w, "couldn't take a screenshot: "+err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(png)
	})
	mux.HandleFunc("PUT /api/file", func(w http.ResponseWriter, r *http.Request) {
		saved, err := save(folder, r.URL.Query().Get("path"), http.MaxBytesReader(w, r.Body, 100<<20))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		log.Printf("saved %s", saved)
		w.Write([]byte(saved))
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Key")), []byte(key)) != 1 {
			time.Sleep(time.Second) // makes guessing the key slow
			http.Error(w, "wrong key", http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

// save writes body to folder/rel, refusing paths that climb out of folder and never replacing an existing file.
func save(folder, rel string, body io.Reader) (string, error) {
	clean := filepath.Clean(filepath.FromSlash("/" + rel))
	if rel == "" || strings.Contains(rel, "..") || clean == string(filepath.Separator) {
		return "", errors.New("bad path")
	}
	target := filepath.Join(folder, clean)
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return "", err
	}
	ext := filepath.Ext(target)
	base := strings.TrimSuffix(target, ext)
	for n := 2; ; n++ {
		f, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
		if errors.Is(err, os.ErrExist) {
			target = fmt.Sprintf("%s (%d)%s", base, n, ext)
			continue
		}
		if err != nil {
			return "", err
		}
		_, err = io.Copy(f, body)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			os.Remove(target)
			return "", err
		}
		return target, nil
	}
}

// loadKey reads the key from the config folder, making one the first time (or when asked).
func loadKey(fresh bool) (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	file := filepath.Join(dir, "issue-mortem", "key")
	if b, err := os.ReadFile(file); err == nil && !fresh && len(strings.TrimSpace(string(b))) > 0 {
		return strings.TrimSpace(string(b)), nil
	}
	// 15 characters from an alphabet with no look-alikes (0/O, 1/l/I), in groups of five.
	const abc = "abcdefghjkmnpqrstuvwxyz23456789"
	raw := make([]byte, 15)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	var k strings.Builder
	for i, b := range raw {
		if i > 0 && i%5 == 0 {
			k.WriteByte('-')
		}
		k.WriteByte(abc[int(b)%len(abc)])
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		return "", err
	}
	return k.String(), os.WriteFile(file, []byte(k.String()+"\n"), 0o600)
}

// addresses returns this computer's Tailscale IPv4 addresses (100.64.0.0/10) and its other ones.
func addresses() (tailscale, other []string) {
	_, tailnet, _ := net.ParseCIDR("100.64.0.0/10")
	ifaces, _ := net.InterfaceAddrs()
	for _, a := range ifaces {
		ipn, ok := a.(*net.IPNet)
		if !ok || ipn.IP.To4() == nil || ipn.IP.IsLoopback() {
			continue
		}
		if tailnet.Contains(ipn.IP) {
			tailscale = append(tailscale, ipn.IP.String())
		} else {
			other = append(other, ipn.IP.String())
		}
	}
	return tailscale, other
}
