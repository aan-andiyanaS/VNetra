/*
 * Firmware MPU6050 - Edge Computing Node
 * Implementasi: Extended Kalman Filter (EKF) 7-State
 * Spesifikasi: A.EKF.1 - A.EKF.4
 */

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <BasicLinearAlgebra.h>

using namespace BLA; // Namespace untuk operasi matriks

// --- Konfigurasi Hardware ---
#define SDA_PIN 1
#define SCL_PIN 2

Adafruit_MPU6050 mpu;

// --- Konstanta Fisika & EKF ---
const float g_const = 9.81f;
const float dt_min = 0.01f; // 10ms Guard Interval
unsigned long last_ts_esp = 0;

// --- Matriks EKF (A.EKF.1) ---
// x = [qw, qx, qy, qz, b_wx, b_wy, b_wz]^T
BLA::Matrix<7, 1> x; 
BLA::Matrix<7, 7> P; // Matriks Kovarians Kesalahan
BLA::Matrix<7, 7> Q; // Matriks Noise Proses
BLA::Matrix<3, 3> R; // Matriks Noise Pengukuran

// Task Handle untuk FreeRTOS
TaskHandle_t EKF_TaskHandle;

// Fungsi inisialisasi State Awal dari Akselerometer (A.EKF.3)
void initEKFState(float ax, float ay, float az) {
  // Hitung sudut awal
  float theta0 = atan2(ay, sqrt(ax*ax + az*az));
  float phi0   = atan2(-ax, az);
  
  // Konversi Euler ke Quaternion (asumsi Yaw awal = 0)
  float cp = cos(theta0 / 2.0f); float sp = sin(theta0 / 2.0f);
  float cr = cos(phi0 / 2.0f);   float sr = sin(phi0 / 2.0f);
  
  x(0) = cr * cp;  // qw
  x(1) = sr * cp;  // qx
  x(2) = cr * sp;  // qy
  x(3) = -sr * sp; // qz
  
  // Inisialisasi Bias ke 0
  x(4) = 0; x(5) = 0; x(6) = 0;

  // Inisialisasi Matriks Kovarians P0 (Identitas)
  P.Fill(0);
  for(int i=0; i<7; i++) P(i,i) = 1.0f;

  // Inisialisasi Matriks Noise Proses Q
  Q.Fill(0);
  for(int i=0; i<4; i++) Q(i,i) = 1e-4f; // Variance Quaternion
  for(int i=4; i<7; i++) Q(i,i) = 1e-3f; // Variance Bias Giroskop

  // Inisialisasi Matriks Noise Pengukuran R (Akselerometer)
  R.Fill(0);
  for(int i=0; i<3; i++) R(i,i) = 0.0025f; 
}

// --- FUNGSI EKF UTAMA (Berjalan di Core 1) ---
void EKF_Task(void *pvParameters) {
  for (;;) {
    unsigned long current_ts_esp = millis();
    float dt = (current_ts_esp - last_ts_esp) / 1000.0f;
    dt = max(dt, dt_min); // A.0 Guard Interval

    if (dt < dt_min) {
      vTaskDelay(1); 
      continue;
    }

    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);

    // Raw input: a dalam m/s^2, w dalam rad/s
    float ax = a.acceleration.x; float ay = a.acceleration.y; float az = a.acceleration.z;
    float wx = g.gyro.x;         float wy = g.gyro.y;         float wz = g.gyro.z;

    // --- A.EKF.2 FASE PREDIKSI ---
    // Laju koreksi giroskop
    float wx_corr = wx - x(4);
    float wy_corr = wy - x(5);
    float wz_corr = wz - x(6);

    // Ekstraksi quaternion saat ini
    float qw = x(0), qx = x(1), qy = x(2), qz = x(3);

    // Prediksi State (Integrasi Diskrit Orde-1)
    x(0) += 0.5f * dt * (-qx*wx_corr - qy*wy_corr - qz*wz_corr);
    x(1) += 0.5f * dt * ( qw*wx_corr - qz*wy_corr + qy*wz_corr);
    x(2) += 0.5f * dt * ( qz*wx_corr + qw*wy_corr - qx*wz_corr);
    x(3) += 0.5f * dt * (-qy*wx_corr + qx*wy_corr + qw*wz_corr);

    // Normalisasi Quaternion Prediksi
    float q_norm = sqrt(x(0)*x(0) + x(1)*x(1) + x(2)*x(2) + x(3)*x(3));
    x(0)/=q_norm; x(1)/=q_norm; x(2)/=q_norm; x(3)/=q_norm;

    // Jacobian Proses (Diubah dari F menjadi F_mat untuk menghindari konflik Macro)
    BLA::Matrix<7, 7> F_mat; F_mat.Fill(0);
    // Blok 4x4 (Turunan Q terhadap Q)
    F_mat(0,0) = 1; F_mat(0,1) = -0.5f*dt*wx_corr; F_mat(0,2) = -0.5f*dt*wy_corr; F_mat(0,3) = -0.5f*dt*wz_corr;
    F_mat(1,0) = 0.5f*dt*wx_corr; F_mat(1,1) = 1; F_mat(1,2) = 0.5f*dt*wz_corr; F_mat(1,3) = -0.5f*dt*wy_corr;
    F_mat(2,0) = 0.5f*dt*wy_corr; F_mat(2,1) = -0.5f*dt*wz_corr; F_mat(2,2) = 1; F_mat(2,3) = 0.5f*dt*wx_corr;
    F_mat(3,0) = 0.5f*dt*wz_corr; F_mat(3,1) = 0.5f*dt*wy_corr; F_mat(3,2) = -0.5f*dt*wx_corr; F_mat(3,3) = 1;
    // Blok 4x3 (Turunan Q terhadap Bias)
    F_mat(0,4) =  0.5f*dt*qx; F_mat(0,5) =  0.5f*dt*qy; F_mat(0,6) =  0.5f*dt*qz;
    F_mat(1,4) = -0.5f*dt*qw; F_mat(1,5) =  0.5f*dt*qz; F_mat(1,6) = -0.5f*dt*qy;
    F_mat(2,4) = -0.5f*dt*qz; F_mat(2,5) = -0.5f*dt*qw; F_mat(2,6) =  0.5f*dt*qx;
    F_mat(3,4) =  0.5f*dt*qy; F_mat(3,5) = -0.5f*dt*qx; F_mat(3,6) = -0.5f*dt*qw;
    // Blok 3x3 bawah tetap Identitas (Bias konstan dalam model proses)
    F_mat(4,4) = 1; F_mat(5,5) = 1; F_mat(6,6) = 1;

    // Update Kovarians Prediksi: P = F_mat * P * F_mat^T + Q
    P = F_mat * P * ~F_mat + Q;

    // --- A.EKF.3 FASE KOREKSI ---
    // Ekstraksi quaternion terprediksi
    qw = x(0); qx = x(1); qy = x(2); qz = x(3);

    // Prediksi Vektor Gravitasi h(x)
    BLA::Matrix<3, 1> hx;
    hx(0) = g_const * 2.0f * (qx*qz - qw*qy);
    hx(1) = g_const * 2.0f * (qw*qx + qy*qz);
    hx(2) = g_const * (qw*qw - qx*qx - qy*qy + qz*qz);

    // Inovasi y_t = z_t - h(x)
    BLA::Matrix<3, 1> z; z(0)=ax; z(1)=ay; z(2)=az;
    BLA::Matrix<3, 1> y = z - hx;

    // Jacobian Pengukuran (H_t)
    BLA::Matrix<3, 7> H; H.Fill(0);
    H(0,0) = -2*qy; H(0,1) =  2*qz; H(0,2) = -2*qw; H(0,3) =  2*qx;
    H(1,0) =  2*qx; H(1,1) =  2*qw; H(1,2) =  2*qz; H(1,3) =  2*qy;
    H(2,0) =  2*qw; H(2,1) = -2*qx; H(2,2) = -2*qy; H(2,3) =  2*qz;
    H *= g_const;

    // Inovasi Kovarians S = H * P * H^T + R
    BLA::Matrix<3, 3> S = H * P * ~H + R;

    // Kalman Gain K = P * H^T * S^-1
    // DIKOREKSI: Memanggil fungsi Inverse(S) alih-alih S.Inverse()
    BLA::Matrix<7, 3> K = P * ~H * Inverse(S);

    // Pembaruan Status Final x = x + K * y
    x += K * y;

    // Pembaruan Kovarians P = (I - K * H) * P
    BLA::Matrix<7, 7> I; I.Fill(0);
    for(int i=0; i<7; i++) I(i,i) = 1.0f;
    P = (I - K * H) * P;

    // Normalisasi Quaternion Pasca-Koreksi
    q_norm = sqrt(x(0)*x(0) + x(1)*x(1) + x(2)*x(2) + x(3)*x(3));
    x(0)/=q_norm; x(1)/=q_norm; x(2)/=q_norm; x(3)/=q_norm;

    // --- A.EKF.4 EKSTRAKSI OUTPUT ---
    // Ekstraksi ulang pasca normalisasi
    qw = x(0); qx = x(1); qy = x(2); qz = x(3);

    // Pitch dan Roll dalam Derajat
    float theta = asin(2.0f * (qw*qy - qz*qx)) * RAD_TO_DEG;
    float phi   = atan2(2.0f * (qw*qx + qy*qz), 1.0f - 2.0f * (qx*qx + qy*qy)) * RAD_TO_DEG;

    // Laju rotasi terkalibrasi (Konversi Rad/s ke Deg/s)
    float wx_corr_deg = (wx - x(4)) * RAD_TO_DEG;
    float wy_corr_deg = (wy - x(5)) * RAD_TO_DEG;
    float wz_corr_deg = (wz - x(6)) * RAD_TO_DEG;

    // Vektor Gravitasi Presisi dan Akselerasi Linear Murni
    float gx = g_const * 2.0f * (qx*qz - qw*qy);
    float gy = g_const * 2.0f * (qw*qx + qy*qz);
    float gz = g_const * (qw*qw - qx*qx - qy*qy + qz*qz);
    float a_lin_mag = sqrt(pow(ax - gx, 2) + pow(ay - gy, 2) + pow(az - gz, 2));

    last_ts_esp = current_ts_esp;

    // --- A.6 PENGIRIMAN PAKET ---
    Serial.print("p_imu:");
    Serial.print(theta, 2); Serial.print(",");
    Serial.print(phi, 2); Serial.print(",");
    Serial.print(wx_corr_deg, 2); Serial.print(",");
    Serial.print(wy_corr_deg, 2); Serial.print(",");
    Serial.print(wz_corr_deg, 2); Serial.print(",");
    Serial.print(a_lin_mag, 3); Serial.print(",");
    Serial.println(current_ts_esp);

    // Jeda dinamis agar total loop mendekati 10ms (100Hz)
    vTaskDelay(pdMS_TO_TICKS(5));
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  // Inisialisasi Bus I2C
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000); 

  if (!mpu.begin(0x68, &Wire)) {
    Serial.println("FATAL: MPU6050 tidak terdeteksi!");
    while (1) { delay(10); } 
  }

  mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
  mpu.setGyroRange(MPU6050_RANGE_250_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 

  // Baca sensor sekali untuk inisialisasi state x_0 (A.EKF.3 bawah)
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  initEKFState(a.acceleration.x, a.acceleration.y, a.acceleration.z);

  Serial.println("EKF Initialized. Starting Core 1 Task...");
  last_ts_esp = millis();

  // Memasukkan fungsi EKF_Task ke Core 1
  xTaskCreatePinnedToCore(
    EKF_Task,         // Fungsi Task
    "EKF_Fusion",     // Nama Task
    8192,             // Ukuran Stack
    NULL,             // Parameter Input
    1,                // Prioritas
    &EKF_TaskHandle,  // Task Handle
    1                 // Ditempelkan pada Core 1
  );
}

void loop() {
  vTaskDelay(pdMS_TO_TICKS(1000));
}