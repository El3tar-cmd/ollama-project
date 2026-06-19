# Ollama Server Android — دليل الإعداد الكامل

> **الهدف:** إنشاء ريبو جديد على GitHub ورفع المشروع عليه وبناء APK تلقائياً عبر GitHub Actions.

---

## المتطلبات

| الأداة | الإصدار المطلوب |
|--------|----------------|
| Git    | أي إصدار حديث  |
| Java   | 17+ (لو هتبني محلياً) |
| Android SDK | API 35+ (لو هتبني محلياً) |

---

## الخطوة 1 — إنشاء ريبو جديد على GitHub

```bash
# 1. افتح GitHub.com → New repository
#    - Repository name: ollama-server-android
#    - Visibility: Private أو Public
#    - لا تختار "Initialize with README" — خليه فاضي
#    - اضغط Create repository
```

بعد الإنشاء هيظهر لك URL الـ SSH بالشكل:
```
git@github.com:YOUR_USERNAME/ollama-server-android.git
```

---

## الخطوة 2 — التأكد من إعداد SSH

بما إنك شغال SSH وعندك المفتاح مضاف للجهاز:

```bash
# اختبر الاتصال بـ GitHub
ssh -T git@github.com
# المفروض يقولك: Hi YOUR_USERNAME! You've successfully authenticated...
```

لو مش شغال:
```bash
# شوف المفاتيح الموجودة
ssh-add -l

# لو فاضي، أضف مفتاحك
ssh-add ~/.ssh/id_ed25519   # أو اسم مفتاحك
```

---

## الخطوة 3 — فك ضغط المشروع وإعداده

```bash
# فك الـ zip في مجلد جديد
tar -xzf ollama-ready.tar.gz
cd ollama-project

# اجعل gradlew قابل للتنفيذ
chmod +x gradlew
```

---

## الخطوة 4 — رفع المشروع على GitHub

```bash
# ابدأ git repo جديد
git init

# أضف ملف .gitignore الموجود (يمنع رفع ملفات البناء)
git add .
git commit -m "Initial commit: Ollama Server Android with AI Agent"

# ربط الريبو بـ GitHub (استبدل YOUR_USERNAME باسمك)
git remote add origin git@github.com:YOUR_USERNAME/ollama-server-android.git

# رفع الكود
git branch -M main
git push -u origin main
```

---

## الخطوة 5 — متابعة بناء الـ APK

بعد الـ push مباشرةً:

1. افتح صفحة الريبو على GitHub
2. اضغط على تبويب **Actions**
3. هتلاقي workflow اسمه **"Build APK"** بدأ تلقائياً
4. اضغط عليه لمتابعة كل خطوة مباشرة

**وقت البناء:** 5–10 دقائق (معظم الوقت في تحميل الـ Binary الـ 48MB)

---

## الخطوة 6 — تحميل الـ APK

بعد ما البناء ينجح:

1. في صفحة الـ workflow، نزّل للأسفل
2. في قسم **Artifacts** هتلاقي ملف اسمه:
   ```
   ollama-server-<git-sha>
   ```
3. اضغط عليه لتحميل ZIP يحتوي على `app-debug.apk`

---

## تشغيل البناء يدوياً (بدون push)

```
GitHub → Actions → Build APK → Run workflow → Run workflow
```

---

## البناء المحلي (اختياري)

لو عندك Android SDK مثبت:

```bash
# ضع Android SDK path في local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# ابني APK
./gradlew assembleDebug

# الناتج في:
# app/build/outputs/apk/debug/app-debug.apk
```

> **ملاحظة:** أول مرة تشغّل، Gradle هيحمّل الـ dependencies تلقائياً.
> لو `./gradlew` مش شغال، شغّل أولاً:
> ```bash
> gradle wrapper --gradle-version=8.14.2
> ```

---

## هيكل الملفات المهمة

```
ollama-project/
├── .github/
│   └── workflows/
│       └── build-apk.yml          ← GitHub Actions workflow
├── app/
│   ├── build.gradle.kts            ← إعدادات البناء (مصلحة)
│   └── src/main/
│       ├── AndroidManifest.xml     ← صلاحيات التطبيق
│       ├── assets/
│       │   └── arm64-v8a/
│       │       └── ollama          ← Binary يُضاف أثناء CI تلقائياً
│       └── java/com/example/
│           ├── MainActivity.kt     ← الواجهة الكاملة (5 tabs)
│           ├── AgentEngine.kt      ← محرك الـ AI Agent
│           ├── OllamaApi.kt        ← Ollama REST client
│           ├── OllamaExecutor.kt   ← تشغيل Binary
│           ├── OllamaService.kt    ← Foreground service
│           ├── SshKeyGen.kt        ← توليد SSH key للـ login
│           └── ui/theme/
│               ├── Color.kt        ← Dark Ollama theme colors
│               └── Theme.kt        ← Force dark theme
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties  ← Gradle 8.14.2
├── .env.example                    ← template لمتغيرات البيئة
├── .gitignore                      ← يمنع رفع ملفات البناء
├── gradlew                         ← تشغيل Gradle على Linux/Mac
└── gradlew.bat                     ← تشغيل Gradle على Windows
```

---

## التعديلات المُطبَّقة على المشروع الأصلي

| الملف | التعديل |
|-------|---------|
| `MainActivity.kt` | تصميم كامل جديد، 5 tabs، bottom nav، dark Ollama theme |
| `AgentEngine.kt` | **ملف جديد** — AI Agent مع tools: read/write/edit/command/search/think |
| `ui/theme/Color.kt` | ألوان Ollama dark (OllamaGreen #00D084, خلفية #0D0D0D) |
| `ui/theme/Theme.kt` | Force dark theme دائماً |
| `app/build.gradle.kts` | إصلاح compileSdk، إزالة CMake، إصلاح signing |
| `AndroidManifest.xml` | إضافة صلاحيات التخزين للـ Agent |
| `.github/workflows/build-apk.yml` | Workflow محسّن ومتكامل |
| `SshKeyGen.kt` | تشفير URL-safe Base64 لإصلاح "Invalid key format" |

---

## مسار العمل للـ AI Agent

الـ Agent يعمل في مجلد العمل الذي تختاره:

- **الافتراضي:** `/sdcard/Android/data/com.aistudio.ollamaserver.hgwtyz/files/OllamaAgent/`
- يمكن التنقل بين المجلدات من تبويب **Files** داخل الـ Agent
- زر **Switch** يبدّل بين internal storage و external storage

الـ Agent يستخدم النموذج المحلي اللي شغّله Ollama كـ LLM backend.

---

## حل المشاكل الشائعة

| المشكلة | الحل |
|---------|------|
| `gradle: command not found` | ثبّت Gradle أو استخدم `./gradlew` |
| `sdk.dir` error | أضف `sdk.dir=$ANDROID_HOME` في `local.properties` |
| Binary download فشل في CI | شغّل الـ workflow يدوياً مرة تانية |
| Invalid key format في Login | التعديل موجود — استخدم URL-safe Base64 |
| Agent مش بيشوف الفايلات | اضغط Refresh في تبويب Files |

---

*آخر تحديث: يونيو 2025*
