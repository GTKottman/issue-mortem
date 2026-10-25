package main

import (
	"bytes"
	"errors"
	"image/png"

	"github.com/kbinani/screenshot"
)

// capture takes every screen as one picture.
func capture() ([]byte, error) {
	n := screenshot.NumActiveDisplays()
	if n == 0 {
		return nil, errors.New("no screens found")
	}
	bounds := screenshot.GetDisplayBounds(0)
	for i := 1; i < n; i++ {
		bounds = bounds.Union(screenshot.GetDisplayBounds(i))
	}
	img, err := screenshot.CaptureRect(bounds)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	err = png.Encode(&buf, img)
	return buf.Bytes(), err
}
