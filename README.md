# 2Tier Android Sync

Android-side sync server for the 2Tier Music System.

## Overview

This project is the Android half of a local-only music sync system:

- Android app = local Wi-Fi sync server
- Desktop app = client/controller
- Transport = HTTP over local Wi-Fi
- Cloud = none

The goal is to sync music files and playlists between desktop and Android without relying on OneDrive or other external services.

## Current Status

### Phase A complete

Implemented and validated on a real Android device:

- SAF-managed music folder selection
- persisted managed-root state
- initial-sync readiness state
- local HTTP server on port `8765`
- device name, IP address, and server status in UI
- `GET /ping`
- `GET /manifest`
- `/manifest` blocked until initial sync is complete
- recursive manifest scanning using:
  - relative path
  - file size
  - last modified

## Verified

Phase A was tested on-device over Wi-Fi:

- app installed and launched successfully
- Sync tab rendered correctly
- managed folder selection persisted
- server started successfully
- `/ping` returned a valid JSON response
- `/manifest` correctly rejected requests before initial sync completion

## Planned Next Steps

### Phase B
- `POST /receive-file`
- `GET /send-file`
- delete operations
- rebuild/index hooks

### Later
- playlist replacement endpoints
- full desktop integration
- batch sync execution

## Notes

This repo is intentionally focused on the Android sync side of the broader 2Tier Music System.

The desktop application will live in a separate repository.