// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android && arm

package sig

/*
#cgo LDFLAGS: -L${SRCDIR}/../libsignal/armeabi-v7a
*/
import "C"
