package main

import (
	"os/exec"
	"strings"
	"syscall"
)

// showInfo shows a dialog with a Copy key button.
func showInfo(text string) {
	q := strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(text)
	out, _ := exec.Command("osascript", "-e",
		`display dialog "`+q+`" with title "Issue Mortem Helper" buttons {"Copy key", "OK"} default button "OK"`).Output()
	if strings.Contains(string(out), "Copy key") {
		for _, line := range strings.Split(text, "\n") {
			if k, ok := strings.CutPrefix(line, "Key:  "); ok {
				c := exec.Command("pbcopy")
				c.Stdin = strings.NewReader(k)
				c.Run()
			}
		}
	}
}

func detach(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
}
