/*
 * Firmware Validasi Sensor VL53L5CX
 * Dependensi: SparkFun_VL53L5CX_Arduino_Library
 */

#include <Wire.h>
#include <SparkFun_VL53L5CX_Library.h>

// --- Definisi Pin Konfigurasi ---
#define SDA_PIN 1
#define SCL_PIN 2
#define INT_PIN 21
#define LPN_PIN 14

SparkFun_VL53L5CX myImager;
VL53L5CX_ResultsData measurementData;

void setup() {
  Serial.begin(115200);
  delay(1000); // Tunggu Serial Monitor siap

  Serial.println("\nMemulai inisialisasi VL53L5CX...");

  // 1. Setup Pin LPn (Low Power / I2C Enable)
  // LPn harus ditarik HIGH agar sensor menyala dan merespon I2C
  pinMode(LPN_PIN, OUTPUT);
  digitalWrite(LPN_PIN, HIGH);
  delay(100); // Waktu transisi boot sensor

  // 2. Inisialisasi I2C dengan Custom Pin (Format ESP32)
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000); // Fast Mode I2C 400kHz untuk transfer data matriks cepat

  // 3. Setup Pin Interrupt (Opsional)
  // Dikonfigurasi sebagai INPUT, meski di script awal ini kita menggunakan polling
  pinMode(INT_PIN, INPUT_PULLUP);

  // 4. Inisialisasi Sensor
  Serial.println("Memuat firmware ke sensor (ini memakan waktu beberapa detik)...");
  if (myImager.begin() == false) {
    Serial.println(F("Gagal menemukan VL53L5CX. Cek wiring, pin I2C, atau pastikan tegangan suplai memadai."));
    while (1) { delay(10); } // Halt system
  }

  // Konfigurasi Parameter Operasional Sensor
  myImager.setResolution(8 * 8); // Set resolusi maksimal 64 zona
  myImager.setRangingFrequency(15); // Kecepatan pembacaan 15 Hz
  myImager.startRanging();
  
  Serial.println(F("Inisialisasi berhasil. Memulai pembacaan data..."));
  Serial.println(F("================================================="));
}

void loop() {
  // Menggunakan metode Polling. Untuk sistem yang efisien daya/CPU, 
  // Anda dapat mengganti ini dengan melampirkan hardware interrupt ke INT_PIN
  if (myImager.isDataReady() == true) {
    if (myImager.getRangingData(&measurementData)) {
      
      // Mencetak data matriks 8x8 dalam format grid
      // Matriks dicetak agar orientasinya sesuai dengan tata letak fisik sensor
      for (int y = 0; y <= 8 * (8 - 1); y += 8) {
        for (int x = 8 - 1; x >= 0; x--) {
          Serial.print(measurementData.distance_mm[x + y]);
          Serial.print(F("\t")); // Tab delimiter agar rapi
        }
        Serial.println();
      }
      Serial.println(F("-------------------------------------------------"));
    }
  }
  
  // Jeda kecil agar tidak melakukan flooding pada bus I2C
  delay(5); 
}