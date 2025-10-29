# BioWave Android App

BioWave is an Android application for real-time biomedical signal monitoring over Bluetooth Low Energy (BLE).  
It connects to the HM-10 BLE module (linked with an STM32-based embedded system) and displays continuous biosignals on the smartphone screen.

---

## Features

- Real-time visualization of:
  - ECG (Electrocardiogram)
  - PPG (Photoplethysmogram)
  - SpO2 (Blood Oxygen Saturation)
  - Heart Rate
  - Body Temperature
- Adaptive Y-scale for both ECG and PPG plots
- Bluetooth LE connection to HM-10 module
- Landscape mode with smooth data plotting
- Automatic reconnection and BLE data parsing
- Clean UI with MPAndroidChart for live signals

---

## System Overview

STM32 (ECG and PPG Acquisition) → HM-10 BLE Module → BioWave Android App

The app receives biosignal data in the format: E:<value>;P:<value>;S:<spo2>\n
and parses it to update ECG and PPG charts in real time.

---

## Technical Details

- Platform: Android (Java)
- Minimum SDK: 23+
- BLE UUIDs:
  - Service: 0000ffe0-0000-1000-8000-00805f9b34fb
  - Characteristic: 0000ffe1-0000-1000-8000-00805f9b34fb
- Target Device: DSD TECH (HM-10 BLE Module)
- Libraries:
  - MPAndroidChart for waveform plotting

---

## Usage

1. Power on the STM32 + HM-10 hardware.
2. Open BioWave on your Android device.
3. Tap START SCAN to connect to the BLE device (DSD TECH).
4. Once connected, live ECG and PPG signals will appear.
5. Toggle “Auto Y” switches to enable or fix scaling for each chart.

---

## Display Example

| Signal | Range (Default) | Auto Y Option |
|:--------|:----------------|:--------------|
| ECG | ±3.0 mV | Adaptive |
| PPG | ±7000 | Adaptive |

---

## Developed For

Part of the Real-Time Embedded Biomedical Signal Acquisition and Processing System  
for wireless biosignal monitoring via STM32, BLE, and Android.

---

Author: Mohsen Anvari  
Email: s.mohsen.anvari.bio@gmail.com  
Website: https://sites.google.com/view/mohsenanvari/home
