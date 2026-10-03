// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android

package alog

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdlib.h>

static void pingme_log(const char *line) {
	__android_log_write(ANDROID_LOG_INFO, "GoLog", line);
}
*/
import "C"

import (
	"bytes"
	"io"
	"sync"
	"unsafe"
)

type androidWriter struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

// Write splits what it gets into lines; each whole line becomes one logcat entry.
func (w *androidWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.buf.Write(p)
	for {
		i := bytes.IndexByte(w.buf.Bytes(), '\n')
		if i < 0 {
			break
		}
		line := string(w.buf.Next(i + 1))
		line = line[:len(line)-1]
		if line != "" {
			cs := C.CString(line)
			C.pingme_log(cs)
			C.free(unsafe.Pointer(cs))
		}
	}
	return len(p), nil
}

var shared io.Writer = &androidWriter{}

func writer() io.Writer { return shared }
