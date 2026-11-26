# جسر تطوير الهاتف — جلسة التطوير (تطبيق منفصل)

تطبيق أندرويد **منفصل تمامًا** عن الجسر الأصلي (`ai.arena.bridgecheck` / `test-device-pairing`)،
مبني من تحليل `base.apk`، حتى تتمكن من التطوير من هذه الجلسة دون لمس الجسر الحالي.

| | الأصلي | هذا التطبيق |
|---|---|---|
| Package | `ai.arena.bridgecheck` | `ai.arena.devbridge` |
| Backend slug | `test-device-pairing` | `dev-session-bridge` |
| Prefs | `dev_bridge` | `dev_bridge_session` |
| STOP action | `ai.arena.bridgecheck.STOP` | `ai.arena.devbridge.STOP` |
| Label | جسر تطوير الهاتف | جسر تطوير الهاتف — جلسة التطوير |

## ما يفعله

- واجهة عربية: اقتران برمز (32 حرفًا hex)، تشغيل/إيقاف الجسر، إعدادات توفير الطاقة.
- خدمة أمامية (Foreground Service) + WakeLock + إعادة تشغيل بعد البووت.
- استطلاع (polling) لـ Supabase Edge Function، وتنفيذ أوامر عبر `su -c` (روت).
- أوامر مدعومة: `ping`، `root_check`، `root_shell`، `disconnect`.

> ⚠️ **تحذير أمان:** عند التشغيل يُسمح بتنفيذ أوامر بصلاحية الروت عبر الإنترنت.
> استخدمه فقط على جهازك الخاص، ولا تشارك رمز الاقتران أو التوكن.

## البناء

### من Android Studio
1. افتح مجلد `dev-bridge/` في Android Studio.
2. انتظر مزامنة Gradle ثم Run على جهازك (روت + Magisk).

### من سطر الأوامر
```bash
cd dev-bridge
chmod +x gradlew
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### تلقائيًا عبر GitHub Actions
ادفع أي تغيير في `dev-bridge/` → يبني `build-dev-bridge.yml` الـ APK ويرفعه كـ Artifact.

## الاقتران (Pairing)

1. ثبّت التطبيق على الهاتف وافتحه — سيولّد `معرّف الجهاز` (UUID) تلقائيًا.
2. أنشئ رمز اقتران في Supabase (يُخزَّن كـ SHA-256 hash فقط):
   ```sql
   -- شغّل في Supabase SQL Editor لمشروع lwmjhmmpsoxukynbfksw
   -- استبدل <CODE> برمز 32 حرفًا hex من اختيارك (16 بايت عشوائية)
   INSERT INTO bridge_pair_codes (code_hash, expires_at)
   VALUES (encode(digest('<CODE>', 'sha256'), 'hex'), now() + interval '1 hour');
   ```
   > ملاحظة: `digest()` يتطلب امتداد `pgcrypto`. إن لم يكن مفعّلًا، احسب الـ hash محليًا
   > (`echo -n '<CODE>' | sha256sum`) والصقه مباشرة.
3. أدخل الرمز في التطبيق واضغط **اقرن الهاتف** → ثم **تشغيل الجسر** ووافق على Magisk.

## إرسال أمر من هنا

```sql
-- استبدل <DEVICE_UUID> بمعرّف الجهاز الظاهر في التطبيق
INSERT INTO bridge_commands (device_id, action, args, status, expires_at)
VALUES ('<DEVICE_UUID>', 'root_shell', '{"command":"id -u"}', 'pending', now() + interval '2 minutes');
-- اقرأ النتيجة:
SELECT status, result, created_at FROM bridge_commands
WHERE device_id = '<DEVICE_UUID>' ORDER BY created_at DESC LIMIT 1;
```

## البنية

```
dev-bridge/
├── app/src/main/java/ai/arena/devbridge/
│   ├── Config.java        # URL + prefs منفصلة لهذه الجلسة
│   ├── MainActivity.java  # واجهة الاقتران/التشغيل (عربي)
│   ├── BridgeService.java # polling + root shell + إشعار
│   └── BootReceiver.java  # إعادة تشغيل بعد البووت
├── supabase/functions/dev-session-bridge/index.ts  # الخادم (slug منفصل)
└── README.md
```

## ملاحظة عن `bot.py`

ملف `bot.py` في جذر المستودع لم يُمسّ — هذا التطبيق منفصل عنه تمامًا.
