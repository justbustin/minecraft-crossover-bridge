#!/bin/zsh
# Double-click to start Elden Ring with the Minecraft bridge (offline, no anti-cheat).
# Start "Steam (Elden Ring)" from ~/Applications/CrossOver first and let it log in.
cd "${0:A:h}"
exec ./scripts/launch_er.sh
