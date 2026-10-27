#!/usr/bin/env bash
# Avoid refreshing package repositories when the hosted runner already has the native runtime.
set -euo pipefail

packages=(xvfb libgl1-mesa-dri libxkbcommon0 libxrandr2 libxinerama1 libxcursor1 libxi6)
missing=()
for package in "${packages[@]}"; do
  status=$(dpkg-query --show --showformat='${db:Status-Status}' "$package" 2>/dev/null || true)
  if [[ "$status" != installed ]]; then
    missing+=("$package")
  fi
done

if ((${#missing[@]})); then
  # Missing dependencies must install successfully; failures remain visible and bounded.
  timeout 120s sudo apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 update -qq
  timeout 120s sudo apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 install -y --no-install-recommends "${missing[@]}"
else
  echo 'Native runtime packages already installed; no repository refresh needed.'
fi
command -v xvfb-run >/dev/null
