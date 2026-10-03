// SPDX-License-Identifier: AGPL-3.0-or-later

// Package alog gives the bridge's loggers a place to write that reaches the phone's log.
// On Android, a process's standard error goes nowhere, so the Go side's log was invisible
// in logcat; here it lands under the tag "GoLog" (owner, Gate G7). Off Android it is
// standard error, as before. Nothing leaves the phone (DESIGN.md 6.5).
package alog

import "io"

// Writer is where a zerolog console writer should send its lines.
func Writer() io.Writer { return writer() }
