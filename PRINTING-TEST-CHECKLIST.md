# v1.3.3 RAWBT Print Test

1. Keep RAWBT installed and its Android Print Service enabled.
2. Pair/configure the same thermal printer in RAWBT.
3. Open Bacong Waterworks and print the same known bill used for v1.3.2 testing.
4. In Android print dialog select RAWBT and the correct 58 mm paper size.
5. Tap Print and do not cancel the Android Print Spooler job.
6. Check whether RAWBT begins faster and whether the printer completes the receipt without a long middle pause.
7. Confirm logo, bill text, totals and receipt width remain correct.
8. Print a second bill immediately to verify repeat behavior.

## Isolation test if timeout remains
- Print the same Bacong bill from Chrome using the same RAWBT service and printer.
- If Chrome also shows RAWBT timeout/mid-print pause, the remaining issue is RAWBT/printer/Bluetooth configuration rather than the APK.
- If Chrome is smooth but v1.3.3 is not, retain the phone/printer details and the exact RAWBT message for another APK-side optimization pass.
