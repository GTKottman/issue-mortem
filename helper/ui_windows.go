package main

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"
)

// showInfo shows a message box. Ctrl+C in it copies its text.
func showInfo(text string) {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString("Issue Mortem Helper")
	windows.MessageBox(0, t, c, windows.MB_OK|windows.MB_ICONINFORMATION)
}

func detach(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.DETACHED_PROCESS | windows.CREATE_NEW_PROCESS_GROUP}
}
