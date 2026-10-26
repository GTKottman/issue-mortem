package main

// Sync on the home network, for people without Tailscale: while syncing, the app also shouts syncHello over UDP
// broadcast to syncPort. The helper answers each shout from a private address by offering itself (name, its own
// address on that network, key) to the shouting device, the same way offerLoop does over Tailscale.

import (
	"fmt"
	"log"
	"net"
	"sync"
	"time"
)

const syncHello = "issue-mortem-sync"

// private reports whether ip is a home-network IPv4 address (10/8, 172.16/12, 192.168/16).
func private(ip net.IP) bool {
	ip4 := ip.To4()
	return ip4 != nil && ip4.IsPrivate()
}

// localAddressFor returns this computer's address on the network that reaches ip.
func localAddressFor(ip net.IP) string {
	c, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: ip, Port: syncPort})
	if err != nil {
		return ""
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).IP.String()
}

// lanLoop runs for as long as the helper serves.
func lanLoop(name, key string, port int) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: syncPort})
	if err != nil {
		log.Printf("home-network sync off: %v", err)
		return
	}
	var mu sync.Mutex
	sent := map[string]time.Time{} // a device that got us is left alone until its sync minute is over
	buf := make([]byte, 64)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		if string(buf[:n]) != syncHello || !private(from.IP) {
			continue
		}
		ip := from.IP.String()
		mu.Lock()
		recent := time.Since(sent[ip]) < 70*time.Second
		if !recent {
			sent[ip] = time.Now()
		}
		mu.Unlock()
		if recent {
			continue
		}
		go func(ip net.IP) {
			self := localAddressFor(ip)
			if self == "" || offer(fmt.Sprintf("http://%s:%d/sync", ip, syncPort), name, fmt.Sprintf("%s:%d", self, port), key) != nil {
				mu.Lock()
				delete(sent, ip.String()) // try again on its next shout
				mu.Unlock()
				return
			}
			log.Printf("synced with %s on the home network", ip)
		}(from.IP)
	}
}
