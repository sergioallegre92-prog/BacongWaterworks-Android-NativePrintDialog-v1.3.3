# Bacong Waterworks Android v1.3.3

## RAWBT throughput optimization

This build keeps the known-working v1.3.2 Android PrintManager -> Print Spooler -> RAWBT flow and does not bypass the Android print dialog.

### What changed
- The completed Bacong receipt HTML is still captured at document.close().
- Before the hidden print WebView creates the Android print document, only the oversized embedded municipal seal is downscaled to a thermal-appropriate maximum of 256 px.
- The logo is recompressed as JPEG at 72% quality. At 58 mm this remains visually appropriate while materially reducing PDF/raster payload.
- Receipt script tags are removed because JavaScript is already disabled in the hidden print WebView and the original delayed window.print() is not needed there.
- QR/barcodes and future non-logo images are deliberately not modified.
- The working v1.3.1 spool-release behavior is preserved.
- Camera/location/notification permission behavior from v1.3.2 is preserved.

### Printing flow
Bacong -> Android Print Dialog -> Android Print Spooler -> RAWBT -> Thermal Printer

The user can still choose printer, paper size, copies, orientation and other settings in the normal Android print dialog.

### Why this update
The website embeds a much larger source logo than a 58 mm thermal printer can physically resolve. Android has to place that image into the print document and RAWBT then rasterizes/transmits it to the printer. Reducing only that unnecessary image payload helps lower RAWBT processing time and Bluetooth transfer pressure without changing bill data or layout.

### Version
- applicationId: online.bacongwaterworks.app
- versionName: 1.3.3
- versionCode: 13
