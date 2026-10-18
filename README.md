# ZipToApk

แอป Android ที่แปลงไฟล์ **.zip ของเว็บ** (HTML/CSS/JS ที่มี `index.html`) ให้เป็น **APK** ติดตั้งได้ — ทำงานในเครื่องทั้งหมด

## วิธีได้ตัวแอป (ไม่ต้องใช้ Android Studio)
1. สร้าง repo บน GitHub แล้วอัปโหลดโฟลเดอร์นี้ทั้งหมด (รวม `.github/`)
2. แท็บ **Actions** → รัน "Build ZipToApk" (รันเองเมื่อ push)
3. เมื่อ build เสร็จ ไปที่แท็บ **Releases** (หรือ artifact **ZipToApk**) → ดาวน์โหลด `app-debug.apk` → ติดตั้ง (บนมือถือเปิดลิงก์ใน Releases ได้เลย)

## วิธีใช้
1. เปิดแอป → "เลือกไฟล์ .zip"
2. ใส่ชื่อแอป (และ package name ถ้าต้องการ — ต้องไม่ซ้ำกันถ้าอยากติดตั้งหลายแอป)
3. "สร้าง APK" → ไฟล์จะอยู่ที่ `Downloads/` → กด "ติดตั้ง"

## หลักการ
- โมดูล `template/` = APK เปล่าที่มี WebView โหลด `assets/www/index.html`
- ตอน build, workflow ฝัง `template.apk` และ keystore ลงใน assets ของแอปหลัก
- แอปหลักคัดลอก template → แทนไฟล์ใน `assets/www/` ด้วยของใน zip → แก้ package/ชื่อใน manifest ไบนารี (`AxmlPatcher`) → เซ็นชื่อ v1+v2 ด้วย apksig

## ข้อจำกัด (เวอร์ชันแรก)
- รองรับเฉพาะโปรเจกต์เว็บ (ไม่ใช่โปรเจกต์ Gradle/Android Studio)
- ไอคอนเป็นไอคอน Android เริ่มต้น, ยังไม่รองรับ `<input type=file>`, กล้อง, GPS
- ทุกแอปที่สร้างถูกเซ็นด้วยคีย์เดียวกัน (คีย์ debug แบบสร้างใน workflow)
- ยังไม่ได้คอมไพล์/ทดสอบบนเครื่องจริง — เฉพาะ `AxmlPatcher` ทดสอบกับไฟล์จำลองแล้ว

## Build ในเครื่อง (ถ้ามี Android Studio / JDK 17)
```
gradle :template:assembleRelease
mkdir -p app/src/main/assets
cp template/build/outputs/apk/release/template-release-unsigned.apk app/src/main/assets/template.apk
keytool -J-Dkeystore.pkcs12.legacy -genkeypair -keystore app/src/main/assets/zip2apk.p12 -storetype PKCS12 \
  -storepass zip2apk -keypass zip2apk -alias zip2apk -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=ZipToApk"
gradle :app:assembleDebug
```
