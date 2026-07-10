/*
 * ESP32-S3 CAM — BLE WiFi Provisioning + WebSocket Camera Stream
 * Phase 4: Unified Firmware
 *
 * Alur:
 *  Boot → Cek flash credentials
 *   ├─ Ada → Auto-connect WiFi → Start WebSocket camera server
 *   └─ Tidak ada → BLE provisioning
 *       └─ Android scan WiFi via BLE → pilih SSID → kirim password
 *           └─ ESP32 connect WiFi → kirim "IP:x.x.x.x" via BLE → BLE off
 *               └─ Start WebSocket camera server
 *
 * Reset WiFi Credentials:
 *   Tahan tombol BOOT (GPIO 0) selama 5 detik.
 *   Indikator LED selama tahan:
 *     0–1.6 s  → Orange  (phase 1/3)
 *     1.6–3.3 s → Kuning  (phase 2/3)
 *     3.3–5 s  → Merah   (phase 3/3)
 *   Setelah 5 s: 6x blink putih cepat → Magenta (sedang reset)
 *   Selesai   → LED biru berkedip (BLE advertising ulang)
 *
 * WebSocket Protocol (binary frame):
 *   [0]     : frame type  (0x01 = JPEG, 0x03 = heartbeat)
 *   [1..8]  : timestamp_us little-endian (uint64_t)
 *   [9..]   : JPEG payload
 *
 * Android endpoint:
 *   ws://[IP]/ws  — WebSocket binary stream
 *
 * Libraries (install via Arduino IDE Library Manager):
 *   - ESPAsyncWebServer by lacamera  (atau me-no-dev)
 *   - AsyncTCP by dvarrel            (atau me-no-dev)
 *   - Adafruit NeoPixel
 *
 * Arduino IDE Settings:
 *   Board        : ESP32S3 Dev Module
 *   PSRAM        : OPI PSRAM
 *   Partition    : Huge APP (3MB No OTA/1MB SPIFFS)
 *   CPU Freq     : 240 MHz
 */

// ======== INCLUDES ========
// Mengatasi konflik nama sensor_t antara esp_camera dan Adafruit_Sensor
#define sensor_t esp_camera_sensor_t
#include "esp_camera.h"
#undef sensor_t
#include "esp_timer.h"
#include <WiFi.h>
#include <esp_wifi.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Adafruit_NeoPixel.h>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>
#include <AsyncUDP.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <BasicLinearAlgebra.h>
#include <SparkFun_VL53L5CX_Library.h>
using namespace BLA;


// ======== KAMERA PIN — ESP32-S3 WROOM N16R8 ========
#define PWDN_GPIO_NUM   -1
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM   15
#define SIOD_GPIO_NUM   4
#define SIOC_GPIO_NUM   5
#define Y9_GPIO_NUM     16
#define Y8_GPIO_NUM     17
#define Y7_GPIO_NUM     18
#define Y6_GPIO_NUM     12
#define Y5_GPIO_NUM     10
#define Y4_GPIO_NUM     8
#define Y3_GPIO_NUM     9
#define Y2_GPIO_NUM     11
#define VSYNC_GPIO_NUM  6
#define HREF_GPIO_NUM   7
#define PCLK_GPIO_NUM   13

// ======== RGB LED — GPIO 48 (WS2812) ========
#define LED_PIN         48
#define NUM_LEDS        1
#define LED_BRIGHTNESS  50
#define BLINK_INTERVAL  500

// ======== SENSOR PIN & CONFIG ========
#define SDA_PIN 1
#define SCL_PIN 2
#define LPN_PIN 14 // VL53L5CX enable pin

SemaphoreHandle_t i2c_mutex;
SemaphoreHandle_t ws_mutex;   // Proteksi ws.binaryAll() dari multiple FreeRTOS tasks
Adafruit_MPU6050 mpu;

// ======== CONFIG ORIENTASI MPU6050 ========
// Aktifkan MPU_MOUNTING_INVERTED jika komponen MPU6050 menghadap ke BAWAH (terbalik)
#define MPU_MOUNTING_INVERTED 

#ifdef MPU_MOUNTING_INVERTED
  // Secara bawaan diasumsikan pembalikan 180 derajat pada sumbu putar longitudinal (roll/Y)
  // sehingga sumbu Z dibalik (Z -> -Z) dan sumbu X dibalik (X -> -X) agar tetap Right-Handed System.
  // Jika pembalikan terjadi pada sumbu lateral (pitch/X), matikan define MPU_FLIP_X_AXIS agar sumbu Y yang dibalik.
  // [MODIFIKASI] Dinonaktifkan (MPU_FLIP_X_AXIS dimatikan) agar sumbu Y yang dibalik, menyesuaikan peletakan sensor fisik yang dibalik lateral.
  // #define MPU_FLIP_X_AXIS
#endif

void getMpuEvent(sensors_event_t *a, sensors_event_t *g, sensors_event_t *temp) {
    mpu.getEvent(a, g, temp);
#ifdef MPU_MOUNTING_INVERTED
    if (a != NULL) {
        a->acceleration.z = -a->acceleration.z;
#ifdef MPU_FLIP_X_AXIS
        a->acceleration.x = -a->acceleration.x;
#else
        a->acceleration.y = -a->acceleration.y;
#endif
    }
    if (g != NULL) {
        g->gyro.z = -g->gyro.z;
#ifdef MPU_FLIP_X_AXIS
        g->gyro.x = -g->gyro.x;
#else
        g->gyro.y = -g->gyro.y;
#endif
    }
#endif
}

SparkFun_VL53L5CX myImager;
VL53L5CX_ResultsData measurementData;

// --- EKF Variables ---
const float g_const = 9.81f;
const float dt_min = 0.01f;
unsigned long last_ts_esp = 0;
BLA::Matrix<7, 1> x_ekf; 
BLA::Matrix<7, 7> P; 
BLA::Matrix<7, 7> Q; 
BLA::Matrix<3, 3> R; 
TaskHandle_t EKF_TaskHandle;
TaskHandle_t TOF_TaskHandle;

// ======== EKF CONVERGENCE TRACKING (Formula A.EKF.5) ========
// ekf_frame_count: jumlah paket IMU WebSocket yang sudah dikirim (~20Hz)
// Konvergensi dianggap setelah EKF_WARMUP_FRAMES paket = 5 detik
// (100 paket × 50ms/paket = 5000ms, sesuai N_warmup spesifikasi)
static volatile uint32_t ekf_frame_count = 0;
static const uint32_t    EKF_WARMUP_FRAMES = 100;  // 100 × 50ms = 5 detik
static const float       DEG2RAD_F = 0.01745329252f;  // π/180, lebih portabel dari M_PI

// ======== TOF RESOLUTION MODE ========
// Resolusi aktif VL53L5CX: 8 (mode 8x8, 64 cell) atau 4 (mode 4x4, 16 cell)
// Diubah via WebSocket command: SET_TOF_MODE:4 / SET_TOF_MODE:8
volatile uint8_t  tofResolution     = 8;   // Default 8x8
volatile bool     tofModeChangePending = false; // Flag: perlu restart ranging

// ======== RESET BUTTON — GPIO 0 (BOOT) ========
#define RESET_BUTTON_PIN 0
#define RESET_HOLD_TIME  5000   // ms — tahan 5 detik untuk reset
#define RESET_PHASE1_MS  1667   // 0     – 1.6 s → LED Orange
#define RESET_PHASE2_MS  3333   // 1.6 s – 3.3 s → LED Kuning
                                // 3.3 s – 5.0 s → LED Merah

// ======== BLE UUIDs — HARUS sama dengan BleManager.kt ========
#define SERVICE_UUID       "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_COMMAND_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_RESPONSE_UUID "cba1d466-344c-4be3-ab3f-189f80dd7518"

// ======== WebSocket FRAME PROTOCOL ========
// Tipe frame — extensible untuk sensor masa depan
#define FRAME_TYPE_JPEG  0x01  // Kamera JPEG
#define FRAME_TYPE_IMU   0x02  // IMU/EKF (MPU6050) — aktif, 9 float × 4B = 36B payload (v2)
#define FRAME_TYPE_HBEAT 0x03  // Heartbeat / keepalive
#define FRAME_TYPE_TOF   0x04  // ToF sensor (VL53L5CX) — aktif, 64 int16_t × 2B = 128B payload
#define FRAME_TYPE_CTRL  0x05  // Control / config command
#define FRAME_HEADER_SZ  9     // 1B type + 8B timestamp_us (little-endian)

// ======== TUNING ========
static constexpr uint8_t  JPEG_QUALITY      = 20;      // 0=best, 63=worst
static constexpr uint32_t TARGET_FRAME_US   = 100000;   // ~10 FPS
static constexpr uint32_t WS_PING_INTERVAL  = 10000;   // ms — heartbeat setiap 10 detik
static constexpr size_t   WS_BUF_MAX        = 130*1024;
static constexpr uint32_t HEAP_GUARD_BYTES  = 30000;

// Dynamic QoS & Frame Dropping
static constexpr unsigned long TARGET_FRAME_MS = 100;     // Target 10 FPS
static constexpr float         MOTION_THRESHOLD = 1.5f;   // Threshold pergerakan IMU (rad/s)
static constexpr uint8_t       QUALITY_STILL    = 12;     // Kualitas saat diam (tajam)
static constexpr uint8_t       QUALITY_MOTION   = 30;     // Kualitas saat bergerak (buram)

// Mode hemat daya: jika tidak ada client selama X ms, skip capture frame
// Kamera tetap init (reinit mahal), hanya frame tidak dikirim
static constexpr uint32_t POWER_SAVE_TIMEOUT = 30000;  // 30 detik tanpa client → hemat daya

// ======== GLOBAL STATE ========

volatile int unacked_frames = 0;
uint32_t last_ack_time = 0;
volatile bool is_moving_fast = false;
unsigned long last_frame_time = 0;
volatile unsigned long last_motion_time = 0;

volatile uint32_t stat_frames_cam = 0;
volatile uint32_t stat_frames_imu = 0;
volatile uint32_t stat_frames_tof = 0;

Adafruit_NeoPixel rgbLed(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
Preferences        preferences;

// WebSocket server
AsyncWebServer  server(80);
AsyncWebSocket  ws("/ws");
volatile bool   wsClientConnected = false;

// UDP Sensor Server
AsyncUDP udpSensor;
const int UDP_TARGET_PORT = 8080;
volatile bool udpClientReady = false;
IPAddress activeClientIp;

// BLE
BLEServer*         pServer       = nullptr;
BLECharacteristic* pCommandChar  = nullptr;
BLECharacteristic* pResponseChar = nullptr;
bool bleActive         = false;
bool deviceConnected   = false;
bool oldDeviceConnected = false;

volatile bool forceResetTriggered = false;

// WiFi
bool   wifiConnected = false;
String deviceIP      = "";
unsigned long wifiDisconnectTime = 0;
bool          isWifiDisconnected   = false;
bool          isCameraActive       = true;
String        currentSSID          = "";
String        currentPassword      = "";

// BLE command flags
bool   shouldScanWifi    = false;
bool   shouldConnectWifi = false;
String pendingSSID       = "";
String pendingPassword   = "";

// Misc
unsigned long previousMillis       = 0;
bool          ledState             = false;
unsigned long resetButtonPressTime = 0;
bool          resetButtonPressed   = false;
bool          resetTriggered       = false;
uint8_t       resetLedPhase        = 0;

// Buffer WebSocket pre-allocated di PSRAM — hindari malloc/free per frame
static uint8_t* g_wsBuf     = nullptr;
static size_t   g_wsBufSize = 0;

// Mode hemat daya
static bool     powerSaveMode       = false;    // true = tidak ada client, skip capture
static uint32_t lastClientLostTime  = 0;        // kapan client terakhir disconnect
static bool     hadClientBefore     = false;    // pernah ada client (untuk trigger power save)

// WiFi Parallel Init Task
static volatile bool wifiInitDone   = false;  // task selesai (berhasil atau gagal)
static volatile bool wifiInitResult = false;  // true = berhasil connect

// ======== LED HELPERS ========
void setLedColor(uint8_t r, uint8_t g, uint8_t b) {
    rgbLed.setPixelColor(0, rgbLed.Color(r, g, b));
    rgbLed.show();
}
void ledOff()    { setLedColor(0,   0,   0);   }
void ledRed()    { setLedColor(255, 0,   0);   }
void ledGreen()  { setLedColor(0,   255, 0);   }
void ledBlue()   { setLedColor(0,   0,   255); }
void ledYellow()  { setLedColor(255, 255, 0);   }
void ledOrange()  { setLedColor(255, 80,  0);   }
void ledMagenta() { setLedColor(255, 0,   180); }
void ledWhite()   { setLedColor(255, 255, 255); }

// ======== PREFERENCES ========
void saveWiFiCredentials(const String& ssid, const String& pass) {
    preferences.begin("wifi", false);
    preferences.putString("ssid",      ssid);
    preferences.putString("password",  pass);
    if (WiFi.status() == WL_CONNECTED) {
        preferences.putBytes("bssid", WiFi.BSSID(), 6);
        preferences.putInt("channel", WiFi.channel());
    }
    preferences.putBool("configured",  true);
    preferences.end();
    Serial.println("[STORAGE] Credentials saved.");
}

bool loadWiFiCredentials(String& ssid, String& pass, uint8_t* bssid, int& channel) {
    preferences.begin("wifi", true);
    bool ok = preferences.getBool("configured", false);
    if (ok) {
        ssid = preferences.getString("ssid",     "");
        pass = preferences.getString("password", "");
        if (preferences.getBytesLength("bssid") == 6) {
            preferences.getBytes("bssid", bssid, 6);
        } else {
            memset(bssid, 0, 6);
        }
        channel = preferences.getInt("channel", 0);
    }
    preferences.end();
    return ok && ssid.length() > 0;
}

void clearWiFiCredentials() {
    preferences.begin("wifi", false);
    preferences.clear();
    preferences.end();
    Serial.println("[STORAGE] Credentials cleared.");
}

// ======== ACCEL BIAS CACHE (NVS) ========
// Menyimpan hasil kalibrasi akselerometer ke NVS agar tidak perlu
// mengulang 500-sample calibration setiap kali device dinyalakan.
void saveAccelBias(const float bias[3]) {
    preferences.begin("sensors", false);
    preferences.putFloat("bias_x", bias[0]);
    preferences.putFloat("bias_y", bias[1]);
    preferences.putFloat("bias_z", bias[2]);
    preferences.putBool("bias_ok", true);
    preferences.end();
    Serial.printf("[CAL] Bias saved to NVS: X=%.4f Y=%.4f Z=%.4f\n",
                  bias[0], bias[1], bias[2]);
}

bool loadAccelBias(float bias[3]) {
    preferences.begin("sensors", true);
    bool ok = preferences.getBool("bias_ok", false);
    if (ok) {
        bias[0] = preferences.getFloat("bias_x", 0.0f);
        bias[1] = preferences.getFloat("bias_y", 0.0f);
        bias[2] = preferences.getFloat("bias_z", 0.0f);
    }
    preferences.end();
    return ok;
}

// ======== CAMERA INIT ========
bool initCamera() {
    camera_config_t cfg = {};
    cfg.ledc_channel = LEDC_CHANNEL_0;
    cfg.ledc_timer   = LEDC_TIMER_0;
    cfg.pin_d0  = Y2_GPIO_NUM; cfg.pin_d1 = Y3_GPIO_NUM;
    cfg.pin_d2  = Y4_GPIO_NUM; cfg.pin_d3 = Y5_GPIO_NUM;
    cfg.pin_d4  = Y6_GPIO_NUM; cfg.pin_d5 = Y7_GPIO_NUM;
    cfg.pin_d6  = Y8_GPIO_NUM; cfg.pin_d7 = Y9_GPIO_NUM;
    cfg.pin_xclk     = XCLK_GPIO_NUM;
    cfg.pin_pclk     = PCLK_GPIO_NUM;
    cfg.pin_vsync    = VSYNC_GPIO_NUM;
    cfg.pin_href     = HREF_GPIO_NUM;
    cfg.pin_sccb_sda = SIOD_GPIO_NUM;
    cfg.pin_sccb_scl = SIOC_GPIO_NUM;
    cfg.pin_pwdn     = PWDN_GPIO_NUM;
    cfg.pin_reset    = RESET_GPIO_NUM;
    cfg.xclk_freq_hz = 24000000;        // 24MHz: max stable clock, readout lebih cepat
    cfg.frame_size   = FRAMESIZE_VGA;   // 640x480 (VGA / 4:3): balance kualitas vs latensi
    cfg.pixel_format = PIXFORMAT_JPEG;
    cfg.jpeg_quality = JPEG_QUALITY;

    if (psramFound()) {
        cfg.fb_location = CAMERA_FB_IN_PSRAM;
        cfg.fb_count    = 3;            // 3 buffer: pipeline lebih smooth, latency berkurang
        cfg.grab_mode   = CAMERA_GRAB_LATEST;
    } else {
        cfg.fb_location = CAMERA_FB_IN_DRAM;
        cfg.fb_count    = 1;
        cfg.frame_size  = FRAMESIZE_QVGA;
        cfg.grab_mode   = CAMERA_GRAB_WHEN_EMPTY;
    }

    if (esp_camera_init(&cfg) != ESP_OK) {
        Serial.println("[CAM] Init FAILED!");
        return false;
    }

    esp_camera_sensor_t* s = esp_camera_sensor_get();
    if (s) {
        s->set_whitebal(s, 1);
        s->set_awb_gain(s, 1);
        s->set_exposure_ctrl(s, 1);
        s->set_aec2(s, 1);
        s->set_gain_ctrl(s, 1);
        s->set_bpc(s, 1);
        s->set_wpc(s, 1);
        s->set_raw_gma(s, 1);
        s->set_lenc(s, 1);
        s->set_gainceiling(s, (gainceiling_t)6);
    }
    Serial.println("[CAM] Init OK.");
    return true;
}

// ======== WEBSOCKET EVENT ========
void onWsEvent(AsyncWebSocket* server, AsyncWebSocketClient* client,
               AwsEventType type, void* arg, uint8_t* data, size_t len) {
    switch (type) {
        case WS_EVT_CONNECT:
            Serial.printf("[WS] Client #%u connected from %s\n",
                          client->id(), client->remoteIP().toString().c_str());
            client->client()->setNoDelay(true);
            activeClientIp = client->remoteIP();
            udpClientReady = true;
            wsClientConnected = true;
            hadClientBefore   = true;
            last_ack_time     = millis();
            unacked_frames    = 0;
            // Keluar dari power save mode saat client baru connect
            if (powerSaveMode) {
                powerSaveMode = false;
                Serial.println("[PWR] Client terhubung - keluar dari mode hemat daya");
                ledGreen();
            }
            break;
        case WS_EVT_DISCONNECT:
            Serial.printf("[WS] Client #%u disconnected\n", client->id());
            // ws.count() sudah terupdate (berkurang 1) saat callback ini dipanggil
            wsClientConnected = (ws.count() > 0);
            if (!wsClientConnected) {
                udpClientReady = false;
                unacked_frames = 0; // reset
            }
            if (!wsClientConnected && hadClientBefore) {
                // Semua client disconnect — catat waktu untuk timer power save
                lastClientLostTime = millis();
                Serial.printf("[PWR] Semua client disconnect — power save dalam %d detik\n",
                              POWER_SAVE_TIMEOUT / 1000);
            }
            break;
        case WS_EVT_ERROR:
            Serial.printf("[WS] Error client #%u\n", client->id());
            break;
        case WS_EVT_DATA: {
            AwsFrameInfo* info = (AwsFrameInfo*)arg;
            if (info->opcode == WS_BINARY && len >= 2 && data[0] == 0xA1) {
                esp_camera_sensor_t* s = esp_camera_sensor_get();
                if (s) s->set_quality(s, data[1]);
                Serial.printf("[WS] JPEG quality → %d\n", data[1]);
            }
            // Command teks: SET_TOF_MODE:4 atau SET_TOF_MODE:8
            if (info->opcode == WS_TEXT && len > 0 && len < 32) {
                // Salin ke buffer null-terminated (data[] mungkin tidak null-terminated)
                char cmdBuf[32] = {0};
                memcpy(cmdBuf, data, len);
                String cmd = String(cmdBuf);
                cmd.trim();
                
                if (cmd.startsWith("PING:")) {
                    String pongReply = "PONG:" + cmd.substring(5);
                    client->text(pongReply);
                } else if (cmd == "ACK:CAM") {
                    last_ack_time = millis();
                    if (unacked_frames > 0) unacked_frames--;
                } else if (cmd == "SET_TOF_MODE:4") {
                    if (tofResolution != 4) {
                        tofResolution = 4;
                        tofModeChangePending = true;
                        Serial.println("[TOF] Mode change requested -> 4x4");
                    }
                } else if (cmd == "SET_TOF_MODE:8") {
                    if (tofResolution != 8) {
                        tofResolution = 8;
                        tofModeChangePending = true;
                        Serial.println("[TOF] Mode change requested -> 8x8");
                    }
                } else if (cmd == "CALIBRATE_IMU") {
                    Serial.println("[CAL] Request calibration. Clearing NVS bias and restarting...");
                    preferences.begin("sensors", false);
                    preferences.remove("bias_ok");
                    preferences.end();
                    delay(500);
                    esp_restart();
                }
            }
            break;
        }
        default: break;
    }
}

// ======== CAPTURE & SEND via WebSocket ========
void captureAndSend() {
    // 3A: Frame Dropping cerdas dengan Fallback
    // last_ack_time diupdate di WS_EVT_DATA setiap menerima ACK:CAM
    extern uint32_t last_ack_time; 
    
    if (millis() - last_ack_time > 3000) {
        // Fallback: Jika tidak ada ACK selama 3 detik, asumsi Android menggunakan app versi lama
        // atau koneksi lag parah. Reset unacked_frames agar video tidak mati total (kembali ke perilaku awal).
        unacked_frames = 0;
    } else if (unacked_frames >= 4) {
        // Flow control ketat: max 4 frame in-flight (~400ms buffer)
        // Jika penuh, DROP frame seketika tanpa delay (agar tidak patah-patah).
        // Jangan di-return di sini karena kita butuh ngecek heap & memori buffer di bawah
        // Tapi untuk performa, return di sini paling hemat CPU. Pastikan fb & jpg_buf belum dialokasi!
        Serial.println("[CAM] Buffer penuh (max 4). Frame didrop.");
        return; 
    }

    // FPS Limiter
    if (millis() - last_frame_time < TARGET_FRAME_MS) {
        return;
    }
    last_frame_time = millis();

    // Skip jika kamera dinonaktifkan sementara
    if (!isCameraActive) return;
    // Skip jika tidak ada client atau dalam mode hemat daya
    if (ws.count() == 0 || powerSaveMode) return;

    if (esp_get_free_heap_size() < HEAP_GUARD_BYTES) {
        Serial.printf("[MEM] Heap kritis (%u B) — frame dilewati\n", esp_get_free_heap_size());
        return;
    }

    // 3B: Dynamic JPEG Quality (Motion-Aware QoS)
    static uint8_t current_sensor_quality = 0;
    uint8_t target_quality = is_moving_fast ? QUALITY_MOTION : QUALITY_STILL;
    if (current_sensor_quality != target_quality) {
        esp_camera_sensor_t* s = esp_camera_sensor_get();
        if (s) {
            s->set_quality(s, target_quality);
            current_sensor_quality = target_quality;
            Serial.printf("[CAM] Dynamic Quality changed to %d (moving: %s)\n", target_quality, is_moving_fast ? "YES" : "NO");
        }
    }

    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return;

    uint8_t* jpg_buf   = fb->buf;
    size_t   jpg_len   = fb->len;
    bool     converted = false;

    if (fb->format != PIXFORMAT_JPEG) {
        converted = frame2jpg(fb, JPEG_QUALITY, &jpg_buf, &jpg_len);
        esp_camera_fb_return(fb);
        fb = nullptr;
        if (!converted) return;
    }

    const size_t   total = FRAME_HEADER_SZ + jpg_len;
    const uint64_t ts_us = esp_timer_get_time();

    // Pre-alokasi permanen satu kali eksekusi
    if (!g_wsBuf) {
        // Alokasikan memori konstan secara absolut
        g_wsBufSize = WS_BUF_MAX; 
        g_wsBuf = (uint8_t*)heap_caps_malloc(
            g_wsBufSize, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
        );
        // Fallback jika PSRAM gagal, gunakan SRAM internal walau kecil kemungkinannya
        if (!g_wsBuf) g_wsBuf = (uint8_t*)malloc(g_wsBufSize);
        
        if (!g_wsBuf) {
            Serial.println("[MEM] GAGAL mengalokasikan buffer statis!");
            g_wsBufSize = 0;
            if (fb)             esp_camera_fb_return(fb);
            else if (converted) free(jpg_buf);
            return;
        }
        Serial.printf("[MEM] Buffer WebSocket Statis Dialokasikan: %u Bytes\n", g_wsBufSize);
    }

    // Jika secara langka ada frame anomali yang lebih besar dari wadah (misal >130KB), buang frame tersebut (Drop)
    if (total > g_wsBufSize) {
        Serial.printf("[CAM] Frame terlalu besar (%u bytes) melebihi buffer statis (%u bytes). Frame didrop.\n", total, g_wsBufSize);
        if (fb)             esp_camera_fb_return(fb);
        else if (converted) free(jpg_buf);
        return;
    }

    g_wsBuf[0] = FRAME_TYPE_JPEG;
    memcpy(g_wsBuf + 1, &ts_us, 8);
    memcpy(g_wsBuf + FRAME_HEADER_SZ, jpg_buf, jpg_len);

    if (fb)             esp_camera_fb_return(fb);
    else if (converted) free(jpg_buf);

    // KRITIS: ws.binaryAll() dipanggil dari loop() DAN dari IMU_Task/TOF_Task
    // ESPAsyncWebServer TIDAK thread-safe — gunakan mutex untuk cegah korupsi
    if (xSemaphoreTake(ws_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        unacked_frames++; // Tandai frame in-flight
        for (auto& client : ws.getClients()) {
            if (client.status() == WS_CONNECTED) {
                // Jangan check queueIsFull lagi, kita sudah limit max 2 frame in-flight di atas
                client.binary(g_wsBuf, total);
            }
        }
        xSemaphoreGive(ws_mutex);
    } else {
        Serial.println("[CAM] Gagal take ws_mutex, frame didrop.");
    }
}

// ======== START WEBSOCKET SERVER ========
void startCameraServer() {
    ws.onEvent(onWsEvent);
    server.addHandler(&ws);
    server.begin();
    if(udpSensor.listen(8081)) {
        Serial.println("[UDP] Sensor Server listening on port 8081");
    }
    Serial.printf("[WS] Server ready — ws://%s/ws\n", deviceIP.c_str());
}

// ======== WIFI CONNECT ========
bool connectToWifi(const String& ssid, const String& pass, const uint8_t* bssid = nullptr, int channel = 0) {
    Serial.printf("[WiFi] Connecting to: %s\n", ssid.c_str());
    ledYellow();
    
    // PERBAIKAN ISU A: Putuskan state radio kotor sebelum connect
    WiFi.disconnect(false);
    delay(100);
    
    WiFi.mode(WIFI_STA);
    
    // Nonaktifkan Wi-Fi Power Save Mode (Modem Sleep) untuk mencegah jitter/latensi tinggi
    esp_wifi_set_ps(WIFI_PS_NONE);
    
    // PERBAIKAN ISU A: Set TX Power maksimal untuk mempercepat association
    WiFi.setTxPower(WIFI_POWER_19_5dBm);

    // Konfigurasi WiFi untuk koneksi stabil
    WiFi.setAutoReconnect(true);  // Auto-reconnect jika sinyal hilang sebentar
    WiFi.persistent(false);       // Jangan simpan ke flash (kita punya NVS sendiri)

    // PERBAIKAN ISU A: Gunakan BSSID dan channel jika valid (skip channel scanning)
    bool hasBssid = (bssid != nullptr) && (bssid[0] != 0 || bssid[1] != 0 || bssid[2] != 0 || bssid[3] != 0 || bssid[4] != 0 || bssid[5] != 0);
    if (channel > 0 && hasBssid) {
        Serial.printf("[WiFi] Fast connect (Channel %d)\n", channel);
        WiFi.begin(ssid.c_str(), pass.c_str(), channel, bssid);
    } else {
        WiFi.begin(ssid.c_str(), pass.c_str());
    }

    int attempts = 0;
    // PERBAIKAN ISU A: Polling lebih cepat 150ms agar tidak telat deteksi WL_CONNECTED
    while (WiFi.status() != WL_CONNECTED && attempts < 133) { // 133 * 150ms ~= 20s
        if (forceResetTriggered) {
            Serial.println("\n[WiFi] Connection aborted by force reset.");
            return false;
        }
        delay(150);
        if (attempts % 4 == 0) Serial.print(".");
        attempts++;
        if (attempts % 4 == 0) ledYellow(); else ledOff();
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        // KRITIKAL: Nonaktifkan WiFi power saving.
        // Default Android/ESP32: WiFi modem bisa masuk sleep mode → packet delay 20-300ms
        // yang menyebabkan TCP timeout dan WebSocket disconnect.
        WiFi.setSleep(false);

        deviceIP     = WiFi.localIP().toString();
        wifiConnected = true;
        currentSSID   = ssid;
        currentPassword = pass;
        isWifiDisconnected = false;

        // Aktifkan kembali kamera jika sebelumnya sempat mati
        if (!isCameraActive) {
            Serial.println("[CAM] Re-initializing camera on successful connection...");
            if (initCamera()) {
                isCameraActive = true;
            }
        }

        Serial.printf("[WiFi] Connected! IP: %s\n", deviceIP.c_str());
        Serial.printf("[WiFi] RSSI: %d dBm | Power saving: OFF\n", WiFi.RSSI());
        return true;
    }
    Serial.println("[WiFi] Connection FAILED.");
    return false;
}

// ======== BLE SCAN WIFI ========
void scanWiFiNetworks() {
    Serial.println("[BLE] Scanning WiFi...");
    ledYellow();

    pResponseChar->setValue("STATUS:Scanning...");
    pResponseChar->notify();
    delay(500);

    int n = WiFi.scanNetworks();

    if (n == 0) {
        pResponseChar->setValue("COUNT:0");
        pResponseChar->notify();
    } else {
        String countMsg = "COUNT:" + String(n);
        pResponseChar->setValue(countMsg.c_str());
        pResponseChar->notify();
        delay(1500);

        String batchMsg  = "";
        int    batchCount = 0;
        const int MAX_BATCH = 180;

        for (int i = 0; i < n; i++) {
            String enc   = (WiFi.encryptionType(i) == WIFI_AUTH_OPEN) ? "O" : "S";
            String entry = String(i) + "|" + WiFi.SSID(i) + "|"
                         + String(WiFi.RSSI(i)) + "|" + enc;

            String test = batchMsg;
            if (test.length() > 0) test += ";";
            test += entry;

            if (test.length() > MAX_BATCH && batchMsg.length() > 0) {
                pResponseChar->setValue(("BATCH:" + batchMsg).c_str());
                pResponseChar->notify();
                delay(800);
                batchMsg   = entry;
                batchCount = 1;
            } else {
                if (batchMsg.length() > 0) batchMsg += ";";
                batchMsg += entry;
                batchCount++;
            }
        }

        if (batchMsg.length() > 0) {
            pResponseChar->setValue(("BATCH:" + batchMsg).c_str());
            pResponseChar->notify();
            delay(500);
        }
    }

    pResponseChar->setValue("STATUS:Done");
    pResponseChar->notify();
    ledGreen();
}

// ======== BLE CONNECT WIFI (dipanggil dari loop) ========
void bleConnectWifi() {
    pResponseChar->setValue("CONNECT:CONNECTING");
    pResponseChar->notify();
    delay(300);

    if (connectToWifi(pendingSSID, pendingPassword)) {
        saveWiFiCredentials(pendingSSID, pendingPassword);

        // Kirim IP agar Android bisa munculkan tombol "View Camera"
        pResponseChar->setValue(("IP:" + deviceIP).c_str());
        pResponseChar->notify();
        delay(800);

        pResponseChar->setValue("CONNECT:SUCCESS");
        pResponseChar->notify();
        delay(800);

        pResponseChar->setValue("BLE:DISCONNECT");
        pResponseChar->notify();
        delay(2000);

        // Matikan BLE
        ledOff();
        pServer->disconnect(pServer->getConnId());
        delay(300);
        BLEDevice::deinit(true);
        bleActive = false;

        // Start WebSocket camera server
        startCameraServer();

    } else {
        pResponseChar->setValue("CONNECT:FAILED:Connection timeout");
        pResponseChar->notify();
        ledRed();
        delay(2000);
        ledGreen();
    }

    pendingSSID     = "";
    pendingPassword = "";
}

// ======== BLE SERVER CALLBACKS ========
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*)    override {
        deviceConnected = true;
        ledGreen();
        Serial.println("[BLE] Client connected.");
    }
    void onDisconnect(BLEServer*) override {
        deviceConnected = false;
        Serial.println("[BLE] Client disconnected.");
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        String value = c->getValue().c_str();
        value.trim();
        Serial.println("[BLE] Cmd: " + value);

        if (value.equalsIgnoreCase("SCAN")) {
            shouldScanWifi = true;
        } else if (value.startsWith("CONNECT:")) {
            String creds = value.substring(8);
            int sep = creds.indexOf('|');
            if (sep > 0) {
                pendingSSID       = creds.substring(0, sep);
                pendingPassword   = creds.substring(sep + 1);
                shouldConnectWifi = true;
            } else {
                pResponseChar->setValue("CONNECT:FAILED:Invalid format");
                pResponseChar->notify();
            }
        } else {
            pResponseChar->setValue("ERROR:Unknown command");
            pResponseChar->notify();
        }
    }
};

// ======== INIT BLE ========
void initBLE() {
    BLEDevice::init("ESP32S3-WiFi-Config");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* svc = pServer->createService(SERVICE_UUID);

    pCommandChar = svc->createCharacteristic(CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE);
    pCommandChar->setCallbacks(new CommandCallbacks());

    pResponseChar = svc->createCharacteristic(CHAR_RESPONSE_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    pResponseChar->addDescriptor(new BLE2902());
    pResponseChar->setValue("Ready");

    svc->start();

    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    bleActive = true;
    ledBlue();
    Serial.println("[BLE] Advertising as ESP32S3-WiFi-Config");
}

// ======== SENSOR TASKS ========
float accel_bias[3] = {0.0f, 0.0f, 0.0f};

void calibrateAccelBias(int n_samples = 200) {
    // ── Cek cache NVS dulu — skip kalibrasi jika sudah pernah dilakukan ──
    // Bias hanya perlu diukur ulang jika device di-remount atau firmware baru.
    // Untuk reset bias: hapus namespace "sensors" dari NVS.
    if (loadAccelBias(accel_bias)) {
        Serial.printf("[CAL] Bias loaded from NVS: X=%.4f Y=%.4f Z=%.4f m/s²\n",
                      accel_bias[0], accel_bias[1], accel_bias[2]);
        return; // skip kalibrasi, hemat ~400ms–1s
    }

    Serial.println("[CAL] Kalibrasi akselerometer — jangan gerakkan device...");
    double sum[3] = {0, 0, 0};

    for (int i = 0; i < n_samples; i++) {
        sensors_event_t a, g, temp;
        if (xSemaphoreTake(i2c_mutex, portMAX_DELAY)) {
            getMpuEvent(&a, &g, &temp);
            xSemaphoreGive(i2c_mutex);
        }
        sum[0] += a.acceleration.x;
        sum[1] += a.acceleration.y;
        sum[2] += a.acceleration.z;
        delay(2);
    }

    float mean[3] = {
        (float)(sum[0] / n_samples),
        (float)(sum[1] / n_samples),
        (float)(sum[2] / n_samples)
    };

    // Ponytail Full: Simplest additive offset. 
    // X and Y should be 0 when flat on table.
    // Z should be g_const (since MPU_MOUNTING_INVERTED flips it to positive).
    accel_bias[0] = mean[0];
    accel_bias[1] = mean[1];
    accel_bias[2] = mean[2] - g_const;

    Serial.printf("[CAL] Accel bias: X=%.4f Y=%.4f Z=%.4f m/s²\n",
                  accel_bias[0], accel_bias[1], accel_bias[2]);

    // Simpan ke NVS agar boot berikutnya langsung load
    saveAccelBias(accel_bias);
}

void initEKFState(float ax, float ay, float az) {
  float theta0 = atan2(ay, sqrt(ax*ax + az*az));
  float phi0   = atan2(-ax, az);
  float cp = cos(theta0 / 2.0f); float sp = sin(theta0 / 2.0f);
  float cr = cos(phi0 / 2.0f);   float sr = sin(phi0 / 2.0f);
  x_ekf(0) = cr * cp; x_ekf(1) = sr * cp; x_ekf(2) = cr * sp; x_ekf(3) = -sr * sp;
  x_ekf(4) = 0; x_ekf(5) = 0; x_ekf(6) = 0;
  P.Fill(0); for(int i=0; i<7; i++) P(i,i) = 1.0f;
  Q.Fill(0); for(int i=0; i<4; i++) Q(i,i) = 1e-4f; for(int i=4; i<7; i++) Q(i,i) = 1e-3f;
  R.Fill(0); for(int i=0; i<3; i++) R(i,i) = 0.0025f; 
}

void IMU_Task(void *pvParameters) {
  TickType_t xLastWakeTime = xTaskGetTickCount();
  for (;;) {
    vTaskDelayUntil(&xLastWakeTime, pdMS_TO_TICKS(5));
    unsigned long current_ts_esp = millis();
    float dt = 0.005f; // Konstan 5ms (200Hz)

    sensors_event_t a, g, temp;
    if (xSemaphoreTake(i2c_mutex, portMAX_DELAY)) {
      getMpuEvent(&a, &g, &temp);
      xSemaphoreGive(i2c_mutex);
    }

    float ax = a.acceleration.x - accel_bias[0];
    float ay = a.acceleration.y - accel_bias[1];
    float az = a.acceleration.z - accel_bias[2];
    float wx = g.gyro.x;         float wy = g.gyro.y;         float wz = g.gyro.z;

    // Hitung magnitudo pergerakan dari gyroscope raw (Dynamic QoS)
    float gyro_mag = sqrtf(wx*wx + wy*wy + wz*wz);
    if (gyro_mag > MOTION_THRESHOLD) {
        last_motion_time = current_ts_esp;
        is_moving_fast = true;
    } else if (current_ts_esp - last_motion_time > 500) {
        is_moving_fast = false;
    }

    float wx_corr = wx - x_ekf(4); float wy_corr = wy - x_ekf(5); float wz_corr = wz - x_ekf(6);
    float qw = x_ekf(0), qx = x_ekf(1), qy = x_ekf(2), qz = x_ekf(3);

    x_ekf(0) += 0.5f * dt * (-qx*wx_corr - qy*wy_corr - qz*wz_corr);
    x_ekf(1) += 0.5f * dt * ( qw*wx_corr - qz*wy_corr + qy*wz_corr);
    x_ekf(2) += 0.5f * dt * ( qz*wx_corr + qw*wy_corr - qx*wz_corr);
    x_ekf(3) += 0.5f * dt * (-qy*wx_corr + qx*wy_corr + qw*wz_corr);

    float q_norm = sqrt(x_ekf(0)*x_ekf(0) + x_ekf(1)*x_ekf(1) + x_ekf(2)*x_ekf(2) + x_ekf(3)*x_ekf(3));
    x_ekf(0)/=q_norm; x_ekf(1)/=q_norm; x_ekf(2)/=q_norm; x_ekf(3)/=q_norm;

    BLA::Matrix<7, 7> F_mat; F_mat.Fill(0);
    F_mat(0,0) = 1; F_mat(0,1) = -0.5f*dt*wx_corr; F_mat(0,2) = -0.5f*dt*wy_corr; F_mat(0,3) = -0.5f*dt*wz_corr;
    F_mat(1,0) = 0.5f*dt*wx_corr; F_mat(1,1) = 1; F_mat(1,2) = 0.5f*dt*wz_corr; F_mat(1,3) = -0.5f*dt*wy_corr;
    F_mat(2,0) = 0.5f*dt*wy_corr; F_mat(2,1) = -0.5f*dt*wz_corr; F_mat(2,2) = 1; F_mat(2,3) = 0.5f*dt*wx_corr;
    F_mat(3,0) = 0.5f*dt*wz_corr; F_mat(3,1) = 0.5f*dt*wy_corr; F_mat(3,2) = -0.5f*dt*wx_corr; F_mat(3,3) = 1;
    F_mat(0,4) =  0.5f*dt*qx; F_mat(0,5) =  0.5f*dt*qy; F_mat(0,6) =  0.5f*dt*qz;
    F_mat(1,4) = -0.5f*dt*qw; F_mat(1,5) =  0.5f*dt*qz; F_mat(1,6) = -0.5f*dt*qy;
    F_mat(2,4) = -0.5f*dt*qz; F_mat(2,5) = -0.5f*dt*qw; F_mat(2,6) =  0.5f*dt*qx;
    F_mat(3,4) =  0.5f*dt*qy; F_mat(3,5) = -0.5f*dt*qx; F_mat(3,6) = -0.5f*dt*qw;
    F_mat(4,4) = 1; F_mat(5,5) = 1; F_mat(6,6) = 1;

    P = F_mat * P * ~F_mat + Q;

    qw = x_ekf(0); qx = x_ekf(1); qy = x_ekf(2); qz = x_ekf(3);
    BLA::Matrix<3, 1> hx;
    hx(0) = g_const * 2.0f * (qx*qz - qw*qy);
    hx(1) = g_const * 2.0f * (qw*qx + qy*qz);
    hx(2) = g_const * (qw*qw - qx*qx - qy*qy + qz*qz);

    BLA::Matrix<3, 1> z; z(0)=ax; z(1)=ay; z(2)=az;
    BLA::Matrix<3, 1> y = z - hx;

    BLA::Matrix<3, 7> H; H.Fill(0);
    H(0,0) = -2*qy; H(0,1) =  2*qz; H(0,2) = -2*qw; H(0,3) =  2*qx;
    H(1,0) =  2*qx; H(1,1) =  2*qw; H(1,2) =  2*qz; H(1,3) =  2*qy;
    H(2,0) =  2*qw; H(2,1) = -2*qx; H(2,2) = -2*qy; H(2,3) =  2*qz;
    H *= g_const;

    BLA::Matrix<3, 3> S = H * P * ~H + R;
    BLA::Matrix<7, 3> K = P * ~H * Inverse(S);

    x_ekf += K * y;
    BLA::Matrix<7, 7> I; I.Fill(0); for(int i=0; i<7; i++) I(i,i) = 1.0f;
    P = (I - K * H) * P;

    q_norm = sqrt(x_ekf(0)*x_ekf(0) + x_ekf(1)*x_ekf(1) + x_ekf(2)*x_ekf(2) + x_ekf(3)*x_ekf(3));
    x_ekf(0)/=q_norm; x_ekf(1)/=q_norm; x_ekf(2)/=q_norm; x_ekf(3)/=q_norm;

    qw = x_ekf(0); qx = x_ekf(1); qy = x_ekf(2); qz = x_ekf(3);
    float theta = asin(2.0f * (qw*qy - qz*qx)) * RAD_TO_DEG;
    float phi   = atan2(2.0f * (qw*qx + qy*qz), 1.0f - 2.0f * (qx*qx + qy*qy)) * RAD_TO_DEG;
    float wx_corr_deg = (wx - x_ekf(4)) * RAD_TO_DEG;
    float wy_corr_deg = (wy - x_ekf(5)) * RAD_TO_DEG;
    float wz_corr_deg = (wz - x_ekf(6)) * RAD_TO_DEG;

    float gx = g_const * 2.0f * (qx*qz - qw*qy);
    float gy = g_const * 2.0f * (qw*qx + qy*qz);
    float gz = g_const * (qw*qw - qx*qx - qy*qy + qz*qz);
    float a_lin_mag = sqrt(pow(ax - gx, 2) + pow(ay - gy, 2) + pow(az - gz, 2));

    last_ts_esp = current_ts_esp;

    // ── A.EKF.5: Pra-komputasi v_head_base (BARU v8.1) ─────────────────────
    // Formula: v_head_base = k_damp × |ω_x^corr| × cos(θ) × π/180  [rad/s]
    // Dikirim ke Mobile untuk digunakan Formula G.1b: v_head^(i) = v_head_base × d_obj^(i)
    // k_damp: faktor redaman saat pengguna menoleh tajam (|ωx| > 5°/s)
    const float OMEGA_X_LIM_DEG = 5.0f;  // °/s — dari Konstanta Sistem
    float k_damp     = (fabsf(wx_corr_deg) > OMEGA_X_LIM_DEG) ? 0.5f : 1.0f;
    float v_head_base = k_damp
                      * (fabsf(wx_corr_deg) * DEG2RAD_F)   // |ω_x^corr| °/s → rad/s
                      * cosf(theta * DEG2RAD_F);            // kompensasi pitch

    // ── Rate-limit UDP send: EKF 200Hz → kirim ~20Hz (setiap 10 iterasi) ──────
    static uint8_t imu_send_tick = 0;
    if (udpClientReady && !powerSaveMode && (++imu_send_tick >= 10)) {
      imu_send_tick = 0;
      ekf_frame_count++;  // Hitung paket IMU dikirim untuk guard is_converged

      // ── A.EKF.5: is_converged — OR antara frame counter dan norma Frobenius P ─
      // Norma Frobenius ||P||_F: cukup bandingkan kuadratnya dengan ε_conv² = 0.01
      // untuk menghindari sqrt() yang relatif mahal di dalam loop 20Hz ini.
      float p_frob_sq = 0.0f;
      for (int _i = 0; _i < 7; _i++)
          for (int _j = 0; _j < 7; _j++)
              p_frob_sq += P(_i, _j) * P(_i, _j);
      bool p_ok  = (p_frob_sq < 0.01f);                    // ||P||_F < 0.10
      bool frm_ok = (ekf_frame_count >= EKF_WARMUP_FRAMES); // frame counter >= 100
      float is_converged = (p_ok || frm_ok) ? 1.0f : 0.0f;

      // ── Payload v2: 9 float × 4B = 36B → total frame = 9B header + 36B = 45B ─
      // Urutan field sesuai Formula A.6:
      // [0]=θ(°)  [1]=φ(°)  [2]=ωx_corr(°/s)  [3]=ωy_corr(°/s)  [4]=ωz_corr(°/s)
      // [5]=‖a_lin‖(m/s²)  [6]=ts_esp_ms(ms)  [7]=v_head_base(rad/s)  [8]=is_converged
      uint8_t imu_buf[45];
      uint64_t ts_us = esp_timer_get_time();
      imu_buf[0] = FRAME_TYPE_IMU;
      memcpy(imu_buf + 1, &ts_us, 8);
      float payload[9] = {
          theta,         phi,                              // [0] [1]
          wx_corr_deg,   wy_corr_deg,   wz_corr_deg,      // [2] [3] [4]
          a_lin_mag,                                       // [5]
          (float)millis(),                                 // [6] ts_esp_ms
          v_head_base,                                     // [7]
          is_converged                                     // [8]
      };
      memcpy(imu_buf + 9, payload, 36);

      AsyncUDPMessage imu_msg(45);
      imu_msg.write(imu_buf, 45);
      udpSensor.sendTo(imu_msg, activeClientIp, UDP_TARGET_PORT);
      stat_frames_imu++; // Counter untuk log statistik
    }

  }
}

void TOF_Task(void *pvParameters) {
  for (;;) {
    // ── Handle mode change request ──────────────────────────────────────────
    if (tofModeChangePending) {
      tofModeChangePending = false;
      uint8_t newRes = tofResolution; // snapshot
      Serial.printf("[TOF] Applying mode change → %dx%d\n", newRes, newRes);
      if (xSemaphoreTake(i2c_mutex, portMAX_DELAY)) {
        myImager.stopRanging();
        vTaskDelay(pdMS_TO_TICKS(100)); // Tunggu sensor settle setelah stopRanging
        Wire.setClock(100000);           // Turunkan clock I2C untuk config ulang
        myImager.setResolution(newRes * newRes);
        // Frequency & Integration Time:
        // Kembali ke nilai default SparkFun yang STABIL.
        // Menurunkan Hz atau menaikkan integration time menyebabkan I2C mutex
        // terblokir terlalu lama → IMU Task tertunda → seluruh pipeline lag.
        //
        // CARA KERJA I2C CONTENTION:
        //   IMU_Task  : butuh mutex setiap 5ms (200Hz)
        //   TOF_Task  : butuh mutex setiap isDataReady() check = setiap 10ms
        //   Integration time 80ms pada 8Hz → sensor "sibuk" 64% cycle time
        //   → IMU_Task terpaksa menunggu → EKF tertunda → WebSocket queue menumpuk
        //
        // Default aman: 4x4=15Hz, 8x8=10Hz, integration time minimal (auto)
        myImager.setRangingFrequency(newRes == 4 ? 15 : 10);
        // Integration time: nilai yang lebih rendah (misal 20/30) dapat mencegah over-saturasi SPAD di bawah sinar matahari (Outdoor).
        // Default aman: 4x4=30ms, 8x8=50ms. Dioptimalkan untuk outdoor: 4x4=20ms, 8x8=30ms.
        myImager.setIntegrationTime(newRes == 4 ? 20 : 30);
        // Ubah urutan target ke STRONGEST untuk mengabaikan ghost object akibat noise cahaya matahari
        myImager.setTargetOrder(SF_VL53L5CX_TARGET_ORDER::STRONGEST);
        
        Wire.setClock(400000);           // Kembalikan ke fast I2C
        myImager.startRanging();
        xSemaphoreGive(i2c_mutex);
      }
      Serial.printf("[TOF] Mode %dx%d aktif.\n", newRes, newRes);
    }

    bool dataReady = false;
    if (xSemaphoreTake(i2c_mutex, portMAX_DELAY)) {
      dataReady = myImager.isDataReady();
      xSemaphoreGive(i2c_mutex);
    }

    if (dataReady) {
      bool gotData = false;
      if (xSemaphoreTake(i2c_mutex, portMAX_DELAY)) {
        gotData = myImager.getRangingData(&measurementData);
        xSemaphoreGive(i2c_mutex);
      }

      if (gotData && udpClientReady && !powerSaveMode) {
        uint8_t  curRes   = tofResolution;           // snapshot untuk konsistensi
        uint16_t numCells = (uint16_t)curRes * curRes; // 16 (4x4) atau 64 (8x8)
        uint16_t distSize = numCells * 2;             // int16_t per cell
        uint16_t statSize = numCells;                 // 1 byte status per cell
        uint16_t totalSize = 1 + 8 + 1 + distSize + statSize;

        // totalSize is at most 1 + 8 + 1 + (64 * 2) + 64 = 202 bytes.
        // We can safely use a stack buffer of 256 bytes.
        uint8_t tof_buf[256];
        if (totalSize <= 256) {
          uint64_t ts_us = esp_timer_get_time();
          tof_buf[0] = FRAME_TYPE_TOF;
          memcpy(tof_buf + 1, &ts_us, 8);
          tof_buf[9] = curRes;  // resolusi (4 atau 8)

          // [M2] Filter target_status sebelum dikirim ke Android.
          // Status yang diterima sebagai data VALID:
          //   5  = VALID RANGE          ← sinyal bersih, akurasi terbaik
          //   6  = WRAP AROUND          ← jarak > 4m, masih bisa dipakai
          //   9  = RANGE VALID MERGED   ← sering terjadi di cell pinggir (sudut FoV besar),
          //                               sigma noise lebih tinggi tapi jarak masih valid
          //
          // Status INVALID (kirim sentinel -1 agar Android tampilkan "–"):
          //   0  = not updated (data lama, belum di-refresh)
          //   1  = sigma fail  (noise terlalu besar, data tidak dapat dipercaya)
          //   4  = phase fail  (interferensi, multi-path, atau target terlalu dekat)
          //   7  = rate fail   (target bergerak sangat cepat)
          //   8  = hardware fail
          //   255 = no target in zone  (tidak ada objek)
          //
          // Rentang jarak valid: 20mm (minimum) – 4000mm (maksimum SparkFun library)
          // Jika jarak di luar rentang ini walau status valid → kirim -1 juga.
          static const uint16_t TOF_MIN_DIST_MM = 20;
          static const uint16_t TOF_MAX_DIST_MM = 4000;

          int16_t filtered_dist[64]; // 64 = max cells (8x8), cukup untuk mode 4x4 (16) juga
          for (uint16_t ci = 0; ci < numCells; ci++) {
            uint8_t  st   = measurementData.target_status[ci];
            int16_t  dist = measurementData.distance_mm[ci];
            // Terima status 5 (valid), 6 (wrap-around), 9 (merged pulse – cell pinggir)
            bool statusOk = (st == 5 || st == 6 || st == 9);
            // Validasi range: dist harus positif dan dalam batas sensor
            bool rangeOk  = (dist >= (int16_t)TOF_MIN_DIST_MM && dist <= (int16_t)TOF_MAX_DIST_MM);
            if (statusOk && rangeOk) {
              filtered_dist[ci] = dist;
            } else {
              // -1 = sentinel: "tidak ada target valid" — bukan error sensor
              filtered_dist[ci] = -1;
            }
          }
          memcpy(tof_buf + 10, filtered_dist, distSize);
          memcpy(tof_buf + 10 + distSize, measurementData.target_status, statSize);

          AsyncUDPMessage tof_msg(totalSize);
          tof_msg.write(tof_buf, totalSize);
          udpSensor.sendTo(tof_msg, activeClientIp, UDP_TARGET_PORT);
        }
      }

      if (gotData) {
        // Auto-Switch logic: hitung rata-rata ambient noise (cahaya luar ruangan)
        uint32_t ambient_sum = 0;
        uint16_t active_cells = tofResolution * tofResolution;
        for (uint16_t ci = 0; ci < active_cells; ci++) {
          ambient_sum += measurementData.ambient_per_spad[ci];
        }
        float ambient_avg = (float)ambient_sum / active_cells;

        const float THRESHOLD_HIGH = 120.0f; // Batas atas (terik matahari) -> 4x4
        const float THRESHOLD_LOW = 50.0f;   // Batas bawah (indoor/teduh) -> 8x8

        if (ambient_avg > THRESHOLD_HIGH && tofResolution == 8) {
          tofResolution = 4;
          tofModeChangePending = true;
          Serial.printf("[TOF] Auto-Switch: Ambient tinggi (%.2f kcps/spad) -> Pindah ke 4x4\n", ambient_avg);
        } else if (ambient_avg < THRESHOLD_LOW && tofResolution == 4) {
          tofResolution = 8;
          tofModeChangePending = true;
          Serial.printf("[TOF] Auto-Switch: Ambient rendah (%.2f kcps/spad) -> Pindah ke 8x8\n", ambient_avg);
        }
      }
    }
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

// ======== WIFI PARALLEL INIT TASK ========
// Struct untuk meneruskan credentials ke task tanpa global sementara
typedef struct {
    char     ssid[64];
    char     pass[64];
    uint8_t  bssid[6];
    int      channel;
    bool     hasCredentials;
} WifiInitParams_t;

void wifiInitTask(void* pvParams) {
    WifiInitParams_t* p = (WifiInitParams_t*)pvParams;

    bool connected = false;
    if (p->hasCredentials) {
        for (int i = 0; i < 3 && !connected; i++) {
            if (forceResetTriggered) break;
            if (connectToWifi(p->ssid, p->pass, p->bssid, p->channel)) {
                connected = true;
            } else if (i < 2) {
                if (forceResetTriggered) break;
                Serial.println("[WiFi] Retry dalam 2 detik...");
                for (int d = 0; d < 20; d++) {
                    if (forceResetTriggered) break;
                    vTaskDelay(pdMS_TO_TICKS(100));
                }
            }
        }
    }

    if (connected && !forceResetTriggered) {
        // ── BUG FIX: startCameraServer dipanggil di sini, bukan di setup() ──
        // Server harus langsung aktif saat WiFi connect agar mobile app
        // tidak timeout menunggu. Setup() masih sibuk dengan sensor init
        // yang bisa 5-10 detik — terlalu lama bagi app yang sudah punya IP.
        startCameraServer();
        ledOff();
        Serial.println("[WS] Server aktif — mobile app bisa connect sekarang.");
        wifiInitResult = connected;
    } else {
        wifiInitResult = false;
    }

    wifiInitDone   = true;
    Serial.println(wifiInitResult
        ? "[WiFi Task] Connected & server ready!"
        : "[WiFi Task] Gagal atau dibatalkan — akan masuk BLE.");
    vTaskDelete(NULL);
}

// ======== TOF DEFERRED INIT TASK ========
// VL53L5CX butuh upload firmware 90KB via I2C 100kHz = 7-10 detik.
// Di-defer ke background task agar tidak memblokir boot path.
// TOF data akan mulai tersedia beberapa detik setelah device siap.
void TOF_InitTask(void* pvParams) {
    Serial.println("[TOF] Background init VL53L5CX...");
    // Pegang mutex selama upload firmware agar tidak tabrakan dengan IMU_Task
    if (xSemaphoreTake(i2c_mutex, portMAX_DELAY) == pdTRUE) {
        Wire.setClock(100000);
        bool ok = false;
        for (int i = 0; i < 3; i++) {
            if (myImager.begin()) {
                ok = true;
                break;
            }
            Serial.println("[WARN] VL53L5CX gagal inisialisasi, mencoba ulang...");
            xSemaphoreGive(i2c_mutex);
            vTaskDelay(pdMS_TO_TICKS(500));
            if (xSemaphoreTake(i2c_mutex, portMAX_DELAY) != pdTRUE) {
                break;
            }
        }
        if (ok) {
            Wire.setClock(400000);
            myImager.setWireMaxPacketSize(128);
            myImager.setResolution(tofResolution * tofResolution); // gunakan mode yang dipilih

            // Frequency & Integration Time:
            // Kembali ke nilai default yang STABIL (tidak memblok I2C mutex).
            // Lihat komentar di mode change handler untuk penjelasan lengkap.
            myImager.setRangingFrequency(tofResolution == 4 ? 15 : 10);
            // Integration time: diturunkan agar lebih tahan terhadap saturasi inframerah dari sinar matahari.
            // Dioptimalkan untuk outdoor: 4x4=20ms, 8x8=30ms.
            myImager.setIntegrationTime(tofResolution == 4 ? 20 : 30);
            // Ubah urutan target ke STRONGEST untuk mengabaikan ghost object akibat noise cahaya matahari
            myImager.setTargetOrder(SF_VL53L5CX_TARGET_ORDER::STRONGEST);

            myImager.startRanging();
            Serial.printf("[TOF] Init: %dx%d, Freq=%dHz, IntTime=%dms\n",
                          tofResolution, tofResolution,
                          (tofResolution == 4 ? 15 : 10),
                          (tofResolution == 4 ? 30 : 50));
        }
        xSemaphoreGive(i2c_mutex);

        if (ok) {
            xTaskCreatePinnedToCore(TOF_Task, "TOF_Task", 6144, NULL, 1, &TOF_TaskHandle, 0); // Pindah ke Core 0
            Serial.println("[OK] VL53L5CX Started (deferred).");
        } else {
            Serial.println("[WARN] VL53L5CX tidak terdeteksi!");
        }
    }
    vTaskDelete(NULL);
}

// ======== BUTTON RESET TASK ========
void ButtonReset_Task(void *pvParameters) {
    unsigned long lastShortReleaseTime = 0;
    for (;;) {
        if (digitalRead(RESET_BUTTON_PIN) == LOW) {
            if (resetTriggered) {
                // Reset sudah dieksekusi — abaikan sampai tombol dilepas terlebih dahulu
            } else if (!resetButtonPressed) {
                // Tombol baru ditekan — catat waktu, mulai phase 1
                resetButtonPressed   = true;
                resetButtonPressTime = millis();
                resetLedPhase        = 0;
                Serial.println("[RESET] Button pressed — tahan 5 detik untuk reset WiFi.");
            } else {
                unsigned long held = millis() - resetButtonPressTime;

                // --- Indikator LED progresif (countdown 3 phase) ---
                if (held < RESET_PHASE1_MS) {
                    // Phase 1: 0 – 1.6 s → Orange
                    if (resetLedPhase != 1) {
                        resetLedPhase = 1;
                        ledOrange();
                        Serial.println("[RESET] Phase 1/3 — Orange");
                    }
                } else if (held < RESET_PHASE2_MS) {
                    // Phase 2: 1.6 – 3.3 s → Kuning
                    if (resetLedPhase != 2) {
                        resetLedPhase = 2;
                        ledYellow();
                        Serial.println("[RESET] Phase 2/3 — Kuning");
                    }
                } else if (held < RESET_HOLD_TIME) {
                    // Phase 3: 3.3 – 5.0 s → Merah
                    if (resetLedPhase != 3) {
                        resetLedPhase = 3;
                        ledRed();
                        Serial.println("[RESET] Phase 3/3 — Merah (segera reset!)");
                    }
                } else {
                    // ======== TRIGGERED: 5 detik tercapai ========
                    Serial.println("[SYSTEM] Reset button held 5s — Clearing WiFi credentials...");
                    resetTriggered = true; // tandai agar loop berikutnya tidak restart countdown
                    forceResetTriggered = true; // Set flag interupsi wifi

                    // A. Feedback visual: 6x blink putih cepat → magenta (sedang proses)
                    for (int i = 0; i < 6; i++) {
                        ledWhite(); delay(80);
                        ledOff();   delay(80);
                    }
                    ledMagenta(); // indikator: sedang memproses reset

                    // 1. Hapus kredensial dari flash (NVS/Preferences)
                    clearWiFiCredentials();

                    wifiConnected = false;
                    deviceIP      = "";

                    // 2. Tutup semua koneksi WebSocket yang masih aktif secara graceful
                    if (xSemaphoreTake(ws_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                        ws.closeAll();
                        xSemaphoreGive(ws_mutex);
                    }
                    wsClientConnected = false;
                    
                    // (wsQueue dihapus)
                    
                    delay(500); // Tambah delay tutup ws

                    // 3. Putuskan WiFi dan matikan radio WiFi sepenuhnya
                    WiFi.disconnect(true);  // true = juga clear AP/STA config internal
                    WiFi.mode(WIFI_OFF);
                    
                    delay(1000);

                    // 4. Deinit BLE jika masih aktif (cegah double-init crash)
                    if (bleActive) {
                        Serial.println("[BLE] Deinit existing BLE stack before re-init...");
                        BLEDevice::deinit(true);
                        bleActive          = false;
                        deviceConnected    = false;
                        oldDeviceConnected = false;
                        pServer            = nullptr;
                        pCommandChar       = nullptr;
                        pResponseChar      = nullptr;
                        delay(200);
                    }

                    // 5. Reset flag BLE command agar tidak ada perintah lama yang tertinggal
                    shouldScanWifi    = false;
                    shouldConnectWifi = false;
                    pendingSSID       = "";
                    pendingPassword   = "";
                    currentSSID       = "";
                    currentPassword   = "";
                    isWifiDisconnected = false;

                    // Aktifkan kembali kamera jika sebelumnya sempat mati sebelum masuk mode BLE provisioning
                    if (!isCameraActive) {
                        Serial.println("[RESET] Re-initializing camera for BLE provisioning mode...");
                        if (initCamera()) {
                            isCameraActive = true;
                        }
                    }

                    // 6. Init ulang BLE dari kondisi bersih
                    Serial.println("[BLE] Re-initializing BLE...");
                    initBLE(); // set bleActive = true, LED biru di dalamnya

                    Serial.println("[SYSTEM] WiFi reset done. BLE advertising aktif.");
                    forceResetTriggered = false; // Reset interupsi flag setelah selesai
                }
            }
        } else {
            // Tombol dilepas
            if (resetTriggered) {
                // Reset telah selesai & tombol baru dilepas — bersihkan semua flag
                resetTriggered     = false;
                resetButtonPressed = false;
                resetLedPhase      = 0;
                Serial.println("[RESET] Tombol dilepas — sistem siap.");
            } else if (resetButtonPressed) {
                unsigned long holdTime = millis() - resetButtonPressTime;
                // Tombol dilepas sebelum 5 detik — batalkan, kembalikan LED
                Serial.println("[RESET] Tombol dilepas sebelum 5 detik — reset dibatalkan.");
                if (wifiConnected)    ledGreen();
                else if (bleActive)   ledBlue();
                else                  ledOff();
                resetButtonPressed = false;
                resetLedPhase      = 0;

                // Double click detection untuk kalibrasi IMU
                if (holdTime < 1000) { // Anggap short press jika < 1 detik
                    if (millis() - lastShortReleaseTime < 600) { // Jeda antar klik < 600ms
                        Serial.println("[CAL] Double click detected! Mengatur ulang bias IMU...");
                        for(int i=0; i<3; i++) {
                            ledBlue(); delay(80);
                            ledOff(); delay(80);
                        }
                        preferences.begin("sensors", false);
                        preferences.remove("bias_ok");
                        preferences.end();
                        delay(500);
                        esp_restart();
                    } else {
                        lastShortReleaseTime = millis();
                    }
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(50)); // Poll every 50ms to reduce CPU usage
    }
}

// ======== SETUP ========
void setup() {
    rgbLed.begin();
    rgbLed.setBrightness(LED_BRIGHTNESS);
    ledOff();
    pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);
    xTaskCreatePinnedToCore(ButtonReset_Task, "ButtonReset", 4096, NULL, 1, NULL, 1);

    Serial.begin(115200);
    delay(1000);
    Serial.println("\n===== ESP32-S3 CAM BLE Provisioning + WebSocket =====");

    i2c_mutex = xSemaphoreCreateMutex();
    ws_mutex  = xSemaphoreCreateMutex();
    // wsQueue dihapus

    // Initialize UDP Sensor Server (ditunda hingga WiFi connected)
    // udpSensor.listen(8081);

    // ── [FAST BOOT] Cek credentials & mulai WiFi di background SEBELUM sensor init ──
    // WiFi connect (terutama BSSID fast-path) bisa ~1 detik;
    // sensor init (kalibrasi + VL53L5CX firmware upload) bisa 5–10 detik.
    // Dengan paralel keduanya, waktu total = max(WiFi, Sensor) bukan jumlahnya.
    static WifiInitParams_t wifiParams;
    memset(&wifiParams, 0, sizeof(wifiParams));
    int ch = 0;
    String tmpSSID, tmpPass;
    if (loadWiFiCredentials(tmpSSID, tmpPass, wifiParams.bssid, ch)) {
        strncpy(wifiParams.ssid, tmpSSID.c_str(), sizeof(wifiParams.ssid) - 1);
        strncpy(wifiParams.pass, tmpPass.c_str(), sizeof(wifiParams.pass) - 1);
        wifiParams.channel       = ch;
        wifiParams.hasCredentials = true;
        Serial.println("[WiFi] Memulai koneksi di background: " + tmpSSID);
        // Jalankan di Core 0 (sama dengan loop), sensor init berjalan di Core 1 via FreeRTOS
        xTaskCreatePinnedToCore(wifiInitTask, "WiFiInit", 4096, &wifiParams, 1, NULL, 0);
    } else {
        wifiInitDone   = true; // tidak ada credentials, langsung selesai
        wifiInitResult = false;
    }

    // ── Inisialisasi Sensor (berjalan paralel dengan WiFi task di atas) ──
    Serial.println("[SENSOR] Initializing I2C & Sensors...");
    pinMode(LPN_PIN, OUTPUT);
    digitalWrite(LPN_PIN, HIGH);
    delay(100); // Tunggu VL53L5CX boot up (diselaraskan dengan issue.md)

    Wire.begin(SDA_PIN, SCL_PIN);
    Wire.setClock(400000);

    bool mpuOk = false;
    for (int i = 0; i < 3; i++) {
        if (mpu.begin(0x68, &Wire)) {
            mpuOk = true;
            break;
        }
        Serial.println("[WARN] MPU6050 gagal inisialisasi, mencoba ulang...");
        delay(500);
    }

    if (!mpuOk) {
        Serial.println("[WARN] MPU6050 tidak terdeteksi setelah 3x percobaan!");
    } else {
        mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
        mpu.setGyroRange(MPU6050_RANGE_250_DEG);
        mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
        calibrateAccelBias();
        sensors_event_t a, g, temp;
        getMpuEvent(&a, &g, &temp);
        initEKFState(a.acceleration.x - accel_bias[0], a.acceleration.y - accel_bias[1], a.acceleration.z - accel_bias[2]);
        last_ts_esp = millis();
        xTaskCreatePinnedToCore(IMU_Task, "IMU_Task", 12288, NULL, 2, &EKF_TaskHandle, 1);
        Serial.println("[OK] MPU6050 & EKF Started.");
    }

    Serial.println("[CAM] Initializing camera...");
    if (!initCamera()) {
        Serial.println("[FATAL] Camera init failed! Halting.");
        ledRed();
        while (true) delay(1000);
    }

    // ── Tunggu WiFi task selesai ──
    // Dalam kondisi normal (BSSID cache valid), WiFi sudah connect
    // jauh sebelum sensor init selesai, jadi loop ini tidak pernah menunggu.
    Serial.println("[WiFi] Menunggu hasil koneksi WiFi background...");
    while (!wifiInitDone && !forceResetTriggered) {
        delay(10);
    }

    if (wifiInitResult && !forceResetTriggered) {
        // startCameraServer() sudah dipanggil di dalam wifiInitTask — tidak perlu lagi di sini.
        Serial.println("[BOOT] WiFi & server sudah aktif.");
    } else if (wifiParams.hasCredentials && !forceResetTriggered) {
        Serial.println("[WiFi] Gagal terkoneksi setelah 3x percobaan. Masuk mode BLE.");
        WiFi.disconnect();
        delay(100);
        initBLE();
    } else if (!forceResetTriggered) {
        WiFi.mode(WIFI_STA);
        WiFi.disconnect();
        delay(100);
        initBLE();
    }

    // ── Defer VL53L5CX init ke background task ──
    // Upload firmware 90KB via I2C ~8 detik berjalan di background.
    // ToF data mulai tersedia setelah task ini selesai.
    xTaskCreatePinnedToCore(TOF_InitTask, "TOFInit", 4096, NULL, 1, NULL, 1);
    Serial.println("[BOOT] Setup selesai. VL53L5CX init berjalan di background.");
}

// ======== LOOP ========
void loop() {
    // BLE provisioning
    if (bleActive) {
        if (shouldScanWifi && deviceConnected) {
            scanWiFiNetworks();
            shouldScanWifi = false;
        }
        if (shouldConnectWifi && deviceConnected) {
            bleConnectWifi();
            shouldConnectWifi = false;
        }

        // Blink biru saat menunggu koneksi BLE
        if (!deviceConnected && !wifiConnected) {
            unsigned long now = millis();
            if (now - previousMillis >= BLINK_INTERVAL) {
                previousMillis = now;
                ledState = !ledState;
                if (ledState) ledBlue(); else ledOff();
            }
        }

        // Re-advertise setelah disconnect
        if (!deviceConnected && oldDeviceConnected && !wifiConnected) {
            delay(500);
            pServer->startAdvertising();
            oldDeviceConnected = deviceConnected;
        }
        if (deviceConnected && !oldDeviceConnected) {
            oldDeviceConnected = deviceConnected;
        }
    }

    // WebSocket camera streaming
    if (wifiConnected && !bleActive) {
        static uint64_t lastFrameUs   = 0;
        static uint32_t lastCleanup   = 0;
        static uint32_t lastHbeat     = 0;
        static uint32_t framesSent    = 0;
        uint64_t nowUs = esp_timer_get_time();
        uint32_t nowMs = millis();

        // ── Power Save Mode: cek timeout jika tidak ada client ────────────
        if (!powerSaveMode && hadClientBefore && !wsClientConnected &&
            lastClientLostTime > 0 &&
            (nowMs - lastClientLostTime >= POWER_SAVE_TIMEOUT)) {
            powerSaveMode = true;
            Serial.println("[PWR] Masuk mode hemat daya — kamera tidak aktif");
            // LED berkedip merah pelan untuk indikasi power save
        }

        // LED indikator power save: berkedip merah pelan
        if (powerSaveMode) {
            static uint32_t lastPwrLed = 0;
            static bool     pwrLedOn   = false;
            if (nowMs - lastPwrLed >= 1500) {  // berkedip setiap 1.5 detik
                lastPwrLed = nowMs;
                pwrLedOn   = !pwrLedOn;
                if (pwrLedOn) ledRed(); else ledOff();
            }
        }

        // (Antrian WebSocket sensor dihapus karena sudah memakai UDP)

        // Capture & kirim frame (dilewati jika powerSaveMode atau tidak ada client)
        if (nowUs - lastFrameUs >= TARGET_FRAME_US) {
            lastFrameUs = nowUs;
            captureAndSend();
            if (!powerSaveMode && wsClientConnected) {
                framesSent++;
                stat_frames_cam++;
            }
        }

        // Bersihkan koneksi WS mati setiap 2 detik
        if (nowMs - lastCleanup >= 2000) {
            lastCleanup = nowMs;
            ws.cleanupClients();
        }

        // ── WiFi Reconnection & Camera Management ────────────────────────
        static uint32_t lastWifiCheck = 0;
        if (nowMs - lastWifiCheck >= 1000) { // Cek status WiFi setiap 1 detik
            lastWifiCheck = nowMs;
            if (WiFi.status() != WL_CONNECTED) {
                if (!isWifiDisconnected) {
                    isWifiDisconnected = true;
                    wifiDisconnectTime = nowMs;
                    Serial.println("[WiFi] Koneksi WiFi terputus! Mencoba menyambung kembali...");
                    WiFi.disconnect();
                    WiFi.begin(currentSSID.c_str(), currentPassword.c_str());
                } else {
                    unsigned long elapsed = nowMs - wifiDisconnectTime;
                    if (elapsed > 30000 && isCameraActive) {
                        Serial.println("[WiFi] Terputus > 30 detik. Menonaktifkan kamera sementara untuk hemat daya...");
                        esp_camera_deinit();
                        isCameraActive = false;
                    }
                }
            } else {
                if (isWifiDisconnected) {
                    isWifiDisconnected = false;
                    Serial.println("[WiFi] Koneksi WiFi berhasil tersambung kembali!");
                    if (!isCameraActive) {
                        Serial.println("[WiFi] Mengaktifkan kembali kamera...");
                        if (initCamera()) {
                            isCameraActive = true;
                        } else {
                            Serial.println("[FATAL] Gagal mengaktifkan kembali kamera!");
                        }
                    }
                }
            }
        }

        // Heartbeat setiap WS_PING_INTERVAL
        if (nowMs - lastHbeat >= WS_PING_INTERVAL) {
            lastHbeat = nowMs;

            // Log statistik yang diperkaya untuk cek aliran data sensor
            Serial.printf("[STAT] Heap: %u B | WS clients: %u | FPS ~%.1f | PowerSave: %s\n",
                esp_get_free_heap_size(),
                ws.count(),
                (float)framesSent * 1000.0f / WS_PING_INTERVAL,
                powerSaveMode ? "ON" : "OFF");
            
            Serial.printf("       [DATA SENT] CAM: %u | IMU: %u | TOF: %u\n", 
                stat_frames_cam, stat_frames_imu, stat_frames_tof);
            
            framesSent = 0;
            stat_frames_cam = 0;
            stat_frames_imu = 0;
            stat_frames_tof = 0;

            // Heartbeat ke client aktif
            if (ws.count() > 0) {
                uint8_t hbeat[FRAME_HEADER_SZ];
                const uint64_t ts = esp_timer_get_time();
                hbeat[0] = FRAME_TYPE_HBEAT;
                memcpy(hbeat + 1, &ts, 8);
                if (xSemaphoreTake(ws_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
                    for (auto& client : ws.getClients()) {
                        if (client.status() == WS_CONNECTED && !client.queueIsFull()) {
                            client.binary(hbeat, FRAME_HEADER_SZ);
                        }
                    }
                    xSemaphoreGive(ws_mutex);
                }
            }
        }
    }

    // yield() agar FreeRTOS watchdog tidak trigger — lebih baik dari delay(5)
    yield();
}