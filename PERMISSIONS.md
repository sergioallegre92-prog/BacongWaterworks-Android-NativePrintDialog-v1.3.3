# Bacong Waterworks Android permissions — v1.3.2

## Runtime permissions
- Location (fine/coarse): used by the existing Bacong reader GPS/geolocation workflow.
- Camera: requested only when a trusted `bacongwaterworks.online` page asks WebView for video/camera capture.
- Notifications (Android 13+): optional; requested once. Declining it does not affect Bacong or printing.

## Automatically granted / normal permissions
- Internet
- Network state

## Intentionally NOT requested
- Bluetooth / Nearby Devices: RAWBT owns the printer connection. Bacong printing uses Android PrintManager -> Print Spooler -> RAWBT.
- Broad storage / photos/media: Android system file picker is used instead, so Bacong does not need unrestricted storage access.
- Microphone: not required by the current Bacong workflow.

Adding permissions does not replace or bypass RAWBT and does not change the v1.3.1 print-spooler handoff.
