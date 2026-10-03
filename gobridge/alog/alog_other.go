// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build !android

package alog

import (
	"io"
	"os"
)

func writer() io.Writer { return os.Stderr }
