# Samsung Printer (SCX-3200 USB OTG Print Studio)

Direct USB OTG print studio and Android System `PrintService` for Samsung SCX-3200 monochrome laser printers.

## Features

- **Direct USB OTG Printing**: Native pure-Kotlin SpliX `Algo0x11` (`0x11`) and ITU-T T.82 `JBIG1` (`0x13`) QPDL v3 raster band encoders over Android USB Host (`Class 0x07`).
- **PDF & Multi-Photo Print Studio**:
  - Resilient 3-tier image decoder supporting JPEG, PNG, WebP, HEIC, AVIF, and Android Photo Picker URIs.
  - Interactive **Photo Size & Layout Studio** with multi-photo grids (`1`, `2`, `4`, `6`, `8`, `9`, and `Auto Grid`), physical print presets (`ID`, `Passport`, `Wallet`, `10×15 cm`, `13×18 cm`, `15×20 cm`, `20×30 cm`), margins, gaps, 3×3 anchor positioning, and cut guides.
  - PDF Page Range selector supporting **All**, **Current**, **Odd**, **Even**, and **Custom** page ranges.
  - Pre-print monochrome laser tuning (Floyd-Steinberg Halftone, Crisp Text threshold, Soft Tone ordered screen, crop, rotate, contrast, and brightness).
- **Android System Print Service**: Print directly from Chrome, Drive, Gallery, and other Android apps when connected via USB OTG.
- **USB Diagnostics & Print History**: IEEE 1284 Device ID and port status telemetry, soft reset, diagnostic test sheet generator, and local print job history.

## Building

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```
