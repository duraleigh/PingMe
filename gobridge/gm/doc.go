// SPDX-License-Identifier: AGPL-3.0-or-later

// Package gm is PingMe's gomobile surface over libgm, the Google Messages library from
// mautrix-gmessages (BUILD_PLAN.md P3.1, DESIGN.md 6.1).
//
// gomobile can only export a flat boundary: strings, byte slices, numbers, errors,
// exported struct pointers, and interfaces declared here. So every value that crosses
// into Kotlin is JSON (the DTO types in convert.go), and everything libgm reports comes
// back through EventSink.OnEvent as one JSON object per event. The Kotlin side
// (connectors/gmessages, GoBridge) turns those into ConnectorEvents.
package gm
