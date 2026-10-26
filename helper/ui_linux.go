package main

import (
	"fmt"
	"os/exec"
	"syscall"
)

// showInfo uses whichever dialog tool the desktop has (zenity on GNOME and most others, kdialog on KDE), else prints.
func showInfo(text string) {
	if _, err := exec.LookPath("zenity"); err == nil {
		if exec.Command("zenity", "--info", "--no-markup", "--title=Issue Mortem Helper", "--text="+text).Run() == nil {
			return
		}
	}
	if _, err := exec.LookPath("kdialog"); err == nil {
		if exec.Command("kdialog", "--title", "Issue Mortem Helper", "--msgbox", text).Run() == nil {
			return
		}
	}
	fmt.Println(text)
}

func hide(c *exec.Cmd) {}

func detach(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
}
