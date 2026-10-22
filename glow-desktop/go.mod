module glow

go 1.22

require github.com/godbus/dbus/v5 v5.1.0

// Vendored copy (BSD-2-Clause) so the build needs no downloads.
replace github.com/godbus/dbus/v5 => ./third_party/godbus
